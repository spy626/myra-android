package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One explicitly authorized, bounded OpenRouter 429 -> Groq Free WEBSITE resend.
 * Chat consent does not cover project source. No other error or a Groq failure retries.
 * Groq cannot enforce an API-side $0 cap; user must keep their Groq account Free.
 */
internal object WorkspaceWebsiteGroqFallback {
    const val PREFERENCE_KEY = "workspace_website_groq_429_opt_in"
    private const val MAX_PROMPT_CHARS = 12_000
    private const val MAX_COMPLETION_TOKENS = 4_500

    // This client deliberately has NO retry interceptor, including after Groq HTTP 429.
    val client: OkHttpClient = WorkspaceFreeAiSuggestion.client.newBuilder()
        .callTimeout(80, TimeUnit.SECONDS)
        .build()

    /** Only definitive free-route HTTP rejections. A timeout or unknown network outcome
     * is NEVER resent to another provider, and an alternate response is NEVER retried.
     * 404 can mean OpenRouter has no free endpoint matching the requested JSON parameters.
     */
    fun routeRejected(code: Int): Boolean = code in setOf(404, 429, 502, 503, 504)

    fun eligible(code: Int, websiteOptIn: Boolean, groqFreeZdrOptIn: Boolean,
                 groqKey: String): Boolean = routeRejected(code) && websiteOptIn && groqFreeZdrOptIn &&
        groqKey.isNotBlank() && groqKey.length <= 256 && groqKey.none(Char::isWhitespace)

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
