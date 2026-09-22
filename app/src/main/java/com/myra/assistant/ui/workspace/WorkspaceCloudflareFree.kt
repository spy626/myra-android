package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Explicitly selected Workers Free inference. Never uses AI Gateway, dynamic routes or paid models.
 * The account's Free plan (not API-side pricing caps) is a user-confirmed prerequisite.
 * No credentials, raw provider error, memory, or source are logged or copied elsewhere.
 */
internal object WorkspaceCloudflareFree {
    const val PREFERENCE_KEY = "workspace_cloudflare_free_direct_opt_in"
    const val MODEL = "@cf/zai-org/glm-4.7-flash"
    private const val ROOT = "https://api.cloudflare.com/client/v4/accounts/"
    private const val PATH = "/ai/run/@cf/zai-org/glm-4.7-flash"
    private const val MAX_BYTES = 130_000L
    private val ACCOUNT = Regex("^[a-fA-F0-9]{32}$")

    fun validAccountId(value: String) = ACCOUNT.matches(value)
    fun validToken(value: String) = value.length in 1..256 &&
        value.none { it.isWhitespace() || it.isISOControl() } && ',' !in value
    fun configured(approved: Boolean, token: String, accountId: String) =
        approved && validToken(token) && validAccountId(accountId)

    fun endpoint(accountId: String): String {
        require(validAccountId(accountId)) { "Enter the 32-character Cloudflare Account ID in API Settings" }
        return ROOT + accountId.lowercase() + PATH
    }

    // Independent client; existing OpenRouter/Groq retry and memory interceptors never run.
    val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun request(token: String, accountId: String, payload: JSONObject,
                        maxCompletion: Int): Request {
        require(validToken(token)) { "Save a valid Cloudflare Workers AI token in API Settings" }
        val url = endpoint(accountId)
        require(maxCompletion in 1..7_000) { "Cloudflare output budget is invalid" }
        val messages = payload.optJSONArray("messages")
        require(messages != null && messages.length() in 1..25) { "Cloudflare request needs selected messages" }
        val last = messages.optJSONObject(messages.length() - 1)
        require(last?.optString("role") == "user" && last.optString("content").isNotBlank()) {
            "Cloudflare request needs a complete user instruction"
        }
        // Fixed Cloudflare-hosted Workers AI model, never Gateway's model router.
        payload.remove("model")
        payload.remove("provider")
        payload.remove("plugins")
        payload.remove("response_format")
        payload.remove("max_tokens")
        // GLM may spend the entire completion budget on non-visible reasoning, leaving
        // content empty even on HTTP 200. Both switches are documented Workers AI inputs.
        // Disable thinking before generation; never display reasoning as a substitute reply.
        payload.put("stream", false).put("max_completion_tokens", maxCompletion)
            .put("store", false).put("reasoning_effort", JSONObject.NULL)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        return Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    fun chatRequest(token: String, accountId: String,
                    messages: List<WorkspaceConversationStore.Message>): Request {
        require(WorkspaceLongInputPolicy.requestFits(messages)) {
            "Complete chat exceeds local budget; nothing was sent"
        }
        val payload = JSONObject(WorkspaceChatGateway.openRouterBody(messages))
        return request(token, accountId, payload, 2_048)
    }

    fun websiteRequest(token: String, accountId: String,
                       snapshot: WorkspaceWebsiteGeneration.Snapshot): Request {
        // Reuse exactly the existing approved goal + three selected source files + instructions.
        val base = WorkspaceWebsiteGeneration.request("local-body-only", snapshot)
        val buffer = Buffer()
        requireNotNull(base.body).writeTo(buffer)
        return request(token, accountId, JSONObject(buffer.readUtf8()), 7_000)
    }

    fun editRequest(token: String, accountId: String, prompt: String): Request {
        val selected = listOf(WorkspaceConversationStore.Message(
            "selected-source", "user", prompt, System.currentTimeMillis()))
        return request(token, accountId,
            JSONObject(WorkspaceChatGateway.openRouterBody(selected)), 3_500)
    }

    private fun status(response: Response): String {
        // Numeric Cloudflare error codes only; never echo its body, token or URL.
        val internal = runCatching {
            JSONObject(response.peekBody(8_193L).string())
                .optJSONArray("errors")?.optJSONObject(0)?.optInt("code", -1)
        }.getOrNull()
        return when {
            response.code == 429 && internal == 3036 ->
                "Cloudflare Workers Free daily neurons exhausted; wait for daily reset. No paid route."
            response.code == 429 && internal == 3040 ->
                "Cloudflare Workers AI capacity busy (HTTP 429); try later. No automatic resend."
            response.code == 429 ->
                "Cloudflare Workers AI HTTP 429: limit or capacity unavailable; no automatic resend."
            response.code == 403 && internal == 5035 ->
                "Cloudflare model requires a paid plan; request stopped. No upgrade or alternate model."
            response.code == 401 || response.code == 403 ->
                "Cloudflare Workers AI HTTP ${response.code}: token, account or model access refused."
            response.code == 400 -> "Cloudflare Workers AI HTTP 400: request format rejected; nothing saved."
            else -> "Cloudflare Workers AI HTTP ${response.code}: request unsuccessful; no paid fallback."
        }
    }

    /** Only visible assistant text. All parts must be text; never consume reasoning,
     * tool arguments, partial output, or an unrecognized multimodal part as chat/code.
     */
    private fun visibleText(raw: Any?): String? = when (raw) {
        is String -> raw.takeIf { it.isNotBlank() }
        is JSONArray -> {
            if (raw.length() == 0) null else {
                val text = StringBuilder()
                for (index in 0 until raw.length()) {
                    val part = raw.optJSONObject(index) ?: return null
                    if (part.optString("type") !in setOf("text", "output_text")) return null
                    val segment = part.opt("text")
                    if (segment !is String) return null
                    text.append(segment)
                }
                text.toString().takeIf { it.isNotBlank() }
            }
        }
        else -> null
    }

    private fun missingTextCategory(raw: Any?): String = when (raw) {
        null, JSONObject.NULL -> "content_missing"
        is String -> "content_empty"
        is JSONArray -> "content_parts_missing_or_invalid"
        else -> "unsupported_content_type"
    }

    private fun readText(response: Response, maxChars: Int): String = response.use { result ->
        require(result.request.url.host == "api.cloudflare.com" &&
            result.request.url.pathSegments.takeLast(5) ==
                listOf("ai", "run", "@cf", "zai-org", "glm-4.7-flash")) {
            "Cloudflare response arrived from an unexpected route; nothing saved"
        }
        require(result.isSuccessful) { status(result) }
        val bytes = result.peekBody(MAX_BYTES + 1).bytes()
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) {
            "Cloudflare reply missing or oversized; nothing saved"
        }
        val root = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("Cloudflare returned invalid JSON [format: invalid_json]; nothing saved") }
        require(!root.has("error") && (!root.has("success") || root.optBoolean("success"))) {
            "Cloudflare refused the request [format: provider_refused]; nothing saved"
        }
        val body = root.optJSONObject("result") ?: root
        val raw: Any?
        if (body.has("choices")) {
            val choices = body.optJSONArray("choices")
            val choice = choices?.optJSONObject(0)
                ?: throw IllegalArgumentException("Cloudflare returned no choice [format: choices_missing]; nothing saved")
            val finish = choice.optString("finish_reason")
            require(finish == "stop") {
                val category = when (finish) {
                    "length" -> "output_limit"
                    "tool_calls" -> "unexpected_tool_call"
                    "content_filter" -> "content_filtered"
                    else -> "unexpected_finish"
                }
                "Cloudflare reply incomplete [format: $category]; nothing saved. No automatic resend."
            }
            val message = choice.optJSONObject("message")
                ?: throw IllegalArgumentException("Cloudflare returned no assistant message [format: message_missing]; nothing saved")
            // Never turn reasoning_content or tool_calls into a user-visible reply.
            raw = message.opt("content")
        } else {
            // Legacy Workers AI synchronous text response, with or without result wrapper.
            raw = body.opt("response")
        }
        val text = visibleText(raw)
            ?: throw IllegalArgumentException(
                "Cloudflare returned no complete text [format: ${missingTextCategory(raw)}]; nothing saved. No automatic resend.")
        require(text.length <= maxChars) {
            "Cloudflare visible reply too large [format: output_oversized]; nothing saved"
        }
        text.trim()
    }

    fun readChat(response: Response): String = readText(response, WorkspaceConversationStore.MAX_MESSAGE_LENGTH)
    fun readEdit(response: Response): String = readText(response, 30_000)
    fun readWebsite(response: Response): Map<String, String> =
        WorkspaceWebsiteGeneration.parse(readText(response, 30_000))
}
