package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One consented, bounded OpenRouter rejection -> Groq Free WEBSITE resend.
 * The website-source opt-in is distinct from Chat consent. Never switch to a paid route.
 * Groq cannot enforce an API-side $0 cap; user must keep their Groq account Free.
 */
internal object WorkspaceWebsiteGroqFallback {
    // Keep the existing preference key for all previously approved website-source sharing.
    const val PREFERENCE_KEY = "workspace_website_groq_429_opt_in"
    // This is a bounded remote envelope, not the historical 500-character task cap.
    private const val MAX_PROMPT_CHARS = WorkspaceLongInputPolicy.MAX_REQUEST_CHARS
    private const val MAX_COMPLETION_TOKENS = 4_500

    // This client deliberately has NO retry interceptor, including after Groq HTTP 429.
    val client: OkHttpClient = WorkspaceFreeAiSuggestion.client.newBuilder()
        .callTimeout(80, TimeUnit.SECONDS)
        .build()

    /** A definitive OpenRouter HTTP 400 rejected the website request, so it is safe
     * to try the already-consented Groq Free route ONCE with the same approved source.
     * 400 may reflect a route/JSON-parameter mismatch, but its exact cause is unknown
     * without a sanitized provider reason. Never retry an uncertain network outcome,
     * key/auth/payment failures, or a rejection by the second provider.
     */
    fun routeRejected(code: Int): Boolean = code in setOf(400, 404, 429, 502, 503, 504)

    fun eligible(code: Int, websiteOptIn: Boolean, groqFreeZdrOptIn: Boolean,
                 groqKey: String): Boolean = routeRejected(code) && websiteOptIn && groqFreeZdrOptIn &&
        groqKey.isNotBlank() && groqKey.length <= 256 && groqKey.none(Char::isWhitespace)

    /** A definitive Groq HTTP 400 can indicate incompatibility with structured-output
     * parameters on a particular free backend. Reuse the exact approved snapshot and
     * key once with documented JSON Object Mode. Local parse/apply remain strict.
     * NEVER use this for timeouts, 429, or a response that may have succeeded.
     */
    fun compatibilityEligible(primary: WorkspaceWebsiteRoute.Provider, code: Int): Boolean =
        primary == WorkspaceWebsiteRoute.Provider.GROQ && code == 400

    fun compatibilityRequest(groqKey: String, snapshot: WorkspaceWebsiteGeneration.Snapshot): Request {
        val original = request(groqKey, snapshot)
        val buffer = Buffer()
        requireNotNull(original.body).writeTo(buffer)
        val payload = JSONObject(buffer.readUtf8())
        payload.put("response_format", JSONObject().put("type", "json_object"))
        return original.newBuilder()
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    /** GPT-OSS 120B supports strict JSON schema; require exactly the three named files.
     * File contents still pass WorkspaceWebsiteGeneration.parse and local safety checks.
     */
    private fun responseFormat(): JSONObject {
        val fileProperties = JSONObject()
        WorkspaceWebsiteGeneration.PATHS.forEach { fileProperties.put(it, JSONObject().put("type", "string")) }
        val files = JSONObject().put("type", "object")
            .put("properties", fileProperties)
            .put("required", JSONArray(WorkspaceWebsiteGeneration.PATHS))
            .put("additionalProperties", false)
        val schema = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("files", files))
            .put("required", JSONArray().put("files"))
            .put("additionalProperties", false)
        return JSONObject().put("type", "json_schema")
            .put("json_schema", JSONObject().put("name", "website_files")
                .put("strict", true).put("schema", schema))
    }

    /** Reuse the exact approved website goal and selected three-file snapshot. Never send
     * chat history, memory, file attachments, secrets or unrelated project directories.
     */
    fun request(groqKey: String, snapshot: WorkspaceWebsiteGeneration.Snapshot): Request {
        require(groqKey.isNotBlank() && groqKey.length <= 256 && groqKey.none(Char::isWhitespace)) {
            "A valid Groq Free key is required"
        }
        // Placeholder constructs the already reviewed body locally; its key is not sent.
        val sourceRequest = WorkspaceWebsiteGeneration.request("local-body-only", snapshot)
        val buffer = Buffer()
        requireNotNull(sourceRequest.body).writeTo(buffer)
        val payload = JSONObject(buffer.readUtf8())
        val messages = payload.getJSONArray("messages")
        val chars = (0 until messages.length()).sumOf {
            messages.getJSONObject(it).getString("content").length
        }
        require(chars <= MAX_PROMPT_CHARS) {
            "Website source is too large for a conservative Groq Free request; existing files unchanged"
        }
        payload.remove("provider")
        payload.remove("plugins")
        payload.remove("max_tokens")
        // GPT-OSS rejects reasoning_format with HTTP 400. Use the documented
        // include_reasoning flag instead; don't leak reasoning into the JSON body.
        payload.remove("reasoning_format")
        payload.put("model", WorkspaceGroqFree.MODEL)
            .put("max_completion_tokens", MAX_COMPLETION_TOKENS)
            .put("reasoning_effort", "low")
            .put("include_reasoning", false)
            .put("response_format", responseFormat())
        return Request.Builder().url(WorkspaceGroqFree.ENDPOINT)
            .header("Authorization", "Bearer $groqKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }
}
