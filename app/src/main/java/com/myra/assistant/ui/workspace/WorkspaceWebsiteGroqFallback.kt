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
    const val PREFERENCE_KEY = "workspace_website_groq_429_opt_in"
    private const val MAX_PROMPT_CHARS = WorkspaceLongInputPolicy.MAX_REQUEST_CHARS
    private const val MAX_COMPLETION_TOKENS = 4_500
    const val MAX_WEBSITE_ATTEMPTS = 3

    fun canAttempt(issued: Int): Boolean = issued in 1 until MAX_WEBSITE_ATTEMPTS

    /** A definite rejection of the first Groq format permits a single format change. */
    fun recoverAfterFallbackGroq(code: Int, issued: Int): Boolean =
        code == 400 && canAttempt(issued)

    // No network-retry interceptor. Uncertain timeouts and 429 are terminal.
    val client: OkHttpClient = WorkspaceFreeAiSuggestion.client.newBuilder()
        .callTimeout(80, TimeUnit.SECONDS)
        .build()

    fun routeRejected(code: Int): Boolean = code in setOf(400, 404, 429, 502, 503, 504)

    fun eligible(code: Int, websiteOptIn: Boolean, groqFreeZdrOptIn: Boolean,
                 groqKey: String): Boolean = routeRejected(code) && websiteOptIn && groqFreeZdrOptIn &&
        groqKey.isNotBlank() && groqKey.length <= 256 && groqKey.none(Char::isWhitespace)

    /** Phone evidence: strict schema and then JSON Object Mode both returned HTTP 400
     * for a NEW website. Earlier text-mode Groq generated files on the same phone.
     * For an EMPTY project or untouched starter, send text-first with explicit JSON-only
     * instructions and the unchanged strict local three-file parser. Existing projects
     * retain their original schema-first contract and protections.
     */
    private fun isFreshWebsite(snapshot: WorkspaceWebsiteGeneration.Snapshot): Boolean =
        snapshot.original.values.all { it.isNullOrBlank() } ||
            (snapshot.original["index.html"]?.contains("<h1>Hello, Workspace!</h1>") == true &&
                snapshot.original["style.css"]?.contains("body { margin: 0; padding: 2rem;") == true &&
                snapshot.original["script.js"]?.contains("// Your JavaScript starts here.") == true)

    fun usesFreshTextMode(snapshot: WorkspaceWebsiteGeneration.Snapshot): Boolean =
        isFreshWebsite(snapshot)

    fun compatibilityEligible(primary: WorkspaceWebsiteRoute.Provider, code: Int): Boolean =
        primary == WorkspaceWebsiteRoute.Provider.GROQ && code == 400

    /** After a definitive first-format HTTP 400, one JSON Object Mode attempt only.
     * Never resend an uncertain outcome, a finished response, or a further format error.
     */
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

    /** The same explicitly approved goal and exactly three selected source snapshots.
     * No history, memory, unrelated files, outside route, or new permission.
     */
    fun request(groqKey: String, snapshot: WorkspaceWebsiteGeneration.Snapshot): Request {
        require(groqKey.isNotBlank() && groqKey.length <= 256 && groqKey.none(Char::isWhitespace)) {
            "A valid Groq Free key is required"
        }
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
        payload.remove("reasoning_format")
        payload.put("model", WorkspaceGroqFree.MODEL)
            .put("max_completion_tokens", MAX_COMPLETION_TOKENS)
            .put("reasoning_effort", "low")
            .put("include_reasoning", false)
        if (isFreshWebsite(snapshot)) {
            // Text mode worked in the preceding phone-verified fresh-site flow. Groq's
            // JSON schema and JSON Object responses have both rejected this exact brief.
            payload.remove("response_format")
        } else payload.put("response_format", responseFormat())
        return Request.Builder().url(WorkspaceGroqFree.ENDPOINT)
            .header("Authorization", "Bearer $groqKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }
}
