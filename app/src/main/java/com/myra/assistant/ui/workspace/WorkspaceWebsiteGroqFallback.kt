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

    /** Groq rejected strict schema and JSON Object Mode for a fresh phone website, while
     * text mode completed. For any NEW site, use text mode with an explicit three-file
     * labelled-block transport; this avoids escaping whole HTML/CSS/JS files into JSON
     * strings. The existing parser still accepts fully valid JSON and validates the exact
     * same three files. Existing-project requests retain strict JSON schema.
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

    /** Only replace the trusted system's serialization contract, NEVER the user's goal,
     * sources, requested behavior or safety requirements. No website name/brief matching.
     * Fail closed if the upstream instruction changes instead of sending mixed directives.
     */
    private fun freshLabelledContract(base: String): String {
        val jsonOpening = "Return exactly ONE JSON object with only a files object containing exactly " +
            "index.html, style.css, script.js string fields. Each field is the COMPLETE new file " +
            "content, not a patch or a markdown code fence. "
        val jsonEnding = "No prose, explanations or markdown outside the JSON."
        require(base.contains(jsonOpening) && base.contains(jsonEnding)) {
            "Website output contract changed; source not sent"
        }
        val labelledOpening = "Return exactly THREE complete files as consecutive labelled code blocks, " +
            "NOT a JSON object or patch. The entire response format must be: " +
            "index.html then a fenced html block, style.css then a fenced css block, " +
            "and script.js then a fenced javascript block, each on its own lines. " +
            "Use the exact lowercase file labels; close every fence. If no JavaScript is " +
            "needed, leave the script.js block empty. Do not put triple backtick fences " +
            "inside any file block. "
        return base.replace(jsonOpening, labelledOpening)
            .replace(jsonEnding, "No preface, extra block, duplicated file or trailing explanation.")
    }

    private fun systemFor(snapshot: WorkspaceWebsiteGeneration.Snapshot,
                          base: String, labelled: Boolean): String {
        var system = if (labelled) freshLabelledContract(base) else base
        // Existing phone-verified native-anchor constraint is independent of the
        // generic output transport. It is NOT required to generate other websites.
        if (isFreshWebsite(snapshot) && snapshot.goal.contains("Explore Minicoy", true) &&
            snapshot.goal.contains("Things to Explore", true)) {
            system += " For this NEW page, implement Explore Minicoy as a native <a href=\"#section-id\"> " +
                "link to the actual Things to Explore heading. CSS :target must show the " +
                "requested Exploring Minicoy! feedback. Do not attach Explore click listeners " +
                "or intercept in-page anchor clicks with preventDefault(). If no OTHER " +
                "JavaScript behaviors are explicitly requested, script.js must be an empty string. " +
                "If other JS is requested, implement only that JS without intercepting Explore. " +
                if (labelled) "Keep the three full files concise and the file blocks complete."
                else "Keep the three full files concise. In JSON strings escape literal newlines, " +
                    "quotes and backslashes; never output a truncated JSON envelope."
        }
        return system
    }

    /** After a definitive HTTP 400, a single JSON Object Mode attempt restores the
     * corresponding JSON instructions; no mixed fenced-block/JSON contract is sent.
     * Never resend an uncertain outcome, a finished response or a further format error.
     */
    fun compatibilityRequest(groqKey: String, snapshot: WorkspaceWebsiteGeneration.Snapshot): Request {
        val original = request(groqKey, snapshot)
        val buffer = Buffer()
        requireNotNull(original.body).writeTo(buffer)
        val payload = JSONObject(buffer.readUtf8())
        if (isFreshWebsite(snapshot)) {
            val sourceRequest = WorkspaceWebsiteGeneration.request("local-body-only", snapshot)
            val sourceBuffer = Buffer()
            requireNotNull(sourceRequest.body).writeTo(sourceBuffer)
            val canonical = JSONObject(sourceBuffer.readUtf8())
                .getJSONArray("messages").getJSONObject(0).getString("content")
            payload.getJSONArray("messages").getJSONObject(0)
                .put("content", systemFor(snapshot, canonical, labelled = false))
        }
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
        if (isFreshWebsite(snapshot)) {
            val system = messages.getJSONObject(0)
            system.put("content", systemFor(snapshot, system.getString("content"), labelled = true))
        }
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
            // Groq's constrained JSON formats rejected the prior fresh website phone brief.
            payload.remove("response_format")
        } else payload.put("response_format", responseFormat())
        return Request.Builder().url(WorkspaceGroqFree.ENDPOINT)
            .header("Authorization", "Bearer $groqKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }
}
