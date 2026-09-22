package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Approved Workers Free inference. Same-account model fallback only for definitive model-level rejections.
 * The account's Free plan (not API-side pricing caps) is a user-confirmed prerequisite.
 * No credentials, raw provider error, memory, or source are logged or copied elsewhere.
 */
internal object WorkspaceCloudflareFree {
    const val PREFERENCE_KEY = "workspace_cloudflare_free_direct_opt_in"
    const val MODEL = "@cf/zai-org/glm-4.7-flash"
    const val MODEL_PREFERENCE_KEY = "workspace_cloudflare_selected_free_model"
    val MODELS = listOf(MODEL, "@cf/qwen/qwen3-30b-a3b-fp8",
        "@cf/google/gemma-4-26b-a4b-it", "@cf/nvidia/nemotron-3-120b-a12b")
    val MODEL_LABELS = listOf("GLM-4.7-Flash", "Qwen3 30B", "Gemma 4 26B", "Nemotron 3 120B")
    fun chosenModel(saved: String?): String = saved?.takeIf { it in MODELS } ?: MODEL
    private const val ROOT = "https://api.cloudflare.com/client/v4/accounts/"
    private const val MAX_BYTES = 130_000L
    private val ACCOUNT = Regex("^[a-fA-F0-9]{32}$")

    fun validAccountId(value: String) = ACCOUNT.matches(value)
    fun validToken(value: String) = value.length in 1..256 &&
        value.none { it.isWhitespace() || it.isISOControl() } && ',' !in value
    fun configured(approved: Boolean, token: String, accountId: String) =
        approved && validToken(token) && validAccountId(accountId)

    fun endpoint(accountId: String, model: String = MODEL): String {
        require(validAccountId(accountId)) { "Enter the 32-character Cloudflare Account ID in API Settings" }
        require(model in MODELS) { "Cloudflare model is not approved for this Free-only selector" }
        return ROOT + accountId.lowercase() + "/ai/run/" + model
    }

    /** Only these documented Cloudflare error codes prove the selected model was unavailable.
     * 3036 is the SHARED account quota: absolutely no model switch on 3036 or unknown 429.
     * A timeout, partial HTTP 200, invalid answer or interruption never enters this method.
     */
    internal fun nextModelAfter(response: Response, current: String): String? {
        if (current !in MODELS || response.isSuccessful) return null
        val code = runCatching {
            JSONObject(response.peekBody(8_193L).string()).optJSONArray("errors")
                ?.optJSONObject(0)?.optInt("code", -1)
        }.getOrNull() ?: return null
        val definiteModelRejection = (response.code == 429 && code == 3040) ||
            (response.code == 404 && code == 3042) ||
            (response.code == 400 && code == 5007)
        return if (definiteModelRejection) MODELS[(MODELS.indexOf(current) + 1) % MODELS.size] else null
    }

    /** Preserve the exact approved text/source while adapting ONLY the next model's schema.
     * Rebuild the POST body because Qwen requires max_tokens, unlike the other models.
     */
    internal fun alternateRequest(original: Request, nextModel: String): Request {
        val url = original.url
        val segments = url.pathSegments
        require(url.scheme == "https" && url.host == "api.cloudflare.com" && url.port == 443 &&
            url.query == null && segments.size == 9 &&
            segments.take(3) == listOf("client", "v4", "accounts") &&
            segments[4] == "ai" && segments[5] == "run") {
            "Cloudflare automatic model switch refused an unexpected route"
        }
        val previous = segments.drop(6).joinToString("/")
        require(previous in MODELS && nextModel in MODELS && previous != nextModel) {
            "Cloudflare automatic model switch refused an unapproved model"
        }
        val originalBody = requireNotNull(original.body) { "Cloudflare model switch requires the same POST body" }
        val buffer = Buffer()
        originalBody.writeTo(buffer)
        require(buffer.size in 1L..200_000L) { "Cloudflare model switch body is missing or oversized" }
        val payload = JSONObject(buffer.readUtf8())
        val budget = if (payload.has("max_tokens")) payload.optInt("max_tokens", -1)
            else payload.optInt("max_completion_tokens", -1)
        require(budget in 1..7_000 && payload.optJSONArray("messages") != null) {
            "Cloudflare model switch has an invalid output budget or messages"
        }
        payload.remove("max_tokens")
        payload.remove("max_completion_tokens")
        payload.remove("store")
        payload.remove("reasoning_effort")
        payload.remove("chat_template_kwargs")
        if (nextModel == MODELS[1]) payload.put("max_tokens", budget)
        else {
            payload.put("max_completion_tokens", budget).put("store", false)
            if (nextModel == MODEL) payload.put("reasoning_effort", JSONObject.NULL)
                .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        }
        return original.newBuilder().url(endpoint(segments[3], nextModel))
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    /** Maximum four allowlisted models, exactly once each, only after definite rejection.
     * A successful response, including an incomplete SSE body, belongs to the original model.
     * Network errors are propagated; there is no uncertain retry or paid-provider escape.
     */
    private class AutomaticFreeModelFallback : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            val segments = request.url.pathSegments
            val initial = segments.drop(6).joinToString("/")
            require(segments.size == 9 && initial in MODELS) {
                "Cloudflare automatic request has an unapproved model route"
            }
            var current = initial
            var attempts = 1
            while (true) {
                val response = chain.proceed(request)
                val next = if (attempts < MODELS.size) nextModelAfter(response, current) else null
                if (next == null) return response
                val alternate = runCatching { alternateRequest(request, next) }
                    .getOrElse { return response }
                response.close()
                request = alternate
                current = next
                attempts++
            }
        }
    }

    // Separate client; no OpenRouter/Groq interceptors, AI Gateway or provider switching.
    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AutomaticFreeModelFallback())
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        // Stream website generation incrementally; a stalled stream still fails safely.
        // The 300s ceiling is total, NOT an unlimited inference or a retry policy.
        .callTimeout(300, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun request(token: String, accountId: String, payload: JSONObject,
                        maxCompletion: Int, model: String = MODEL, website: Boolean = false): Request {
        require(validToken(token)) { "Save a valid Cloudflare Workers AI token in API Settings" }
        val url = endpoint(accountId, model)
        require(maxCompletion in 1..7_000) { "Cloudflare output budget is invalid" }
        val messages = payload.optJSONArray("messages")
        require(messages != null && messages.length() in 1..25) { "Cloudflare request needs selected messages" }
        val last = messages.optJSONObject(messages.length() - 1)
        require(last?.optString("role") == "user" && last.optString("content").isNotBlank()) {
            "Cloudflare request needs a complete user instruction"
        }
        // Exact user-selected, allowlisted Cloudflare-hosted model; never AI Gateway.
        payload.remove("model")
        payload.remove("provider")
        payload.remove("plugins")
        payload.remove("response_format")
        payload.remove("max_tokens")
        payload.remove("reasoning_effort")
        payload.remove("chat_template_kwargs")
        payload.remove("store")
        payload.put("stream", website)
        if (model == MODELS[1]) {
            // Qwen's native Workers AI schema uses max_tokens, not max_completion_tokens.
            payload.put("max_tokens", maxCompletion)
        } else {
            payload.put("max_completion_tokens", maxCompletion).put("store", false)
            if (model == MODEL) {
                // GLM-specific documented switch: prevent invisible reasoning consuming
                // the whole output budget; do not send this to other model schemas.
                payload.put("reasoning_effort", JSONObject.NULL)
                    .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            }
        }
        return Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    fun chatRequest(token: String, accountId: String,
                    messages: List<WorkspaceConversationStore.Message>,
                    model: String = MODEL): Request {
        require(WorkspaceLongInputPolicy.requestFits(messages)) {
            "Complete chat exceeds local budget; nothing was sent"
        }
        val payload = JSONObject(WorkspaceChatGateway.openRouterBody(messages))
        return request(token, accountId, payload, 2_048, model)
    }

    fun websiteRequest(token: String, accountId: String,
                       snapshot: WorkspaceWebsiteGeneration.Snapshot,
                       model: String = MODEL): Request {
        // Reuse exactly the existing approved goal + three selected source files + instructions.
        val base = WorkspaceWebsiteGeneration.request("local-body-only", snapshot)
        val buffer = Buffer()
        requireNotNull(base.body).writeTo(buffer)
        return request(token, accountId, JSONObject(buffer.readUtf8()), 7_000, model, website = true)
    }

    fun editRequest(token: String, accountId: String, prompt: String,
                    model: String = MODEL): Request {
        val selected = listOf(WorkspaceConversationStore.Message(
            "selected-source", "user", prompt, System.currentTimeMillis()))
        return request(token, accountId,
            JSONObject(WorkspaceChatGateway.openRouterBody(selected)), 3_500, model)
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
    private fun visibleText(raw: Any?): String? {
        return when (raw) {
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
    }

    private fun missingTextCategory(raw: Any?): String = when (raw) {
        null, JSONObject.NULL -> "content_missing"
        is String -> "content_empty"
        is JSONArray -> "content_parts_missing_or_invalid"
        else -> "unsupported_content_type"
    }

    private fun assertApprovedRoute(response: Response) {
        val segments = response.request.url.pathSegments
        require(response.request.url.host == "api.cloudflare.com" &&
            segments.takeLast(5).take(2) == listOf("ai", "run") &&
            segments.takeLast(3).joinToString("/") in MODELS) {
            "Cloudflare response arrived from an unexpected route; nothing saved"
        }
    }

    private fun readText(response: Response, maxChars: Int): String = response.use { result ->
        assertApprovedRoute(result)
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
    /** Website-only SSE: bounded incremental bytes, no partial writes or uncertain retries.
     * Read and validate the COMPLETE finish event before invoking the existing local validator.
     * Both the SSE and synchronous API response shape are supported for compatibility.
     */
    fun readWebsite(response: Response): Map<String, String> {
        if (!response.isSuccessful ||
            !response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)) {
            return WorkspaceWebsiteGeneration.parse(readText(response, 30_000))
        }
        return response.use { result ->
            assertApprovedRoute(result)
            val source = result.body?.source()
                ?: throw IllegalArgumentException("Cloudflare website stream missing; no files changed")
            val text = StringBuilder()
            var totalChars = 0
            var done = false
            var sawChoice = false
            var completed = false
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    totalChars += line.length
                    require(totalChars <= MAX_BYTES) { "Cloudflare website stream oversized; no files changed" }
                    if (!line.startsWith("data:")) continue
                    val data = line.substringAfter("data:").trim()
                    if (data == "[DONE]") { done = true; break }
                    if (data.isBlank()) continue
                    val event = runCatching { JSONObject(data) }
                        .getOrElse { throw IllegalArgumentException("Cloudflare website stream invalid JSON; no files changed") }
                    require(!event.has("error") && (!event.has("success") || event.optBoolean("success"))) {
                        "Cloudflare website stream refused; no files changed"
                    }
                    val body = event.optJSONObject("result") ?: event
                    val choice = body.optJSONArray("choices")?.optJSONObject(0)
                    val piece = if (choice != null) {
                        sawChoice = true
                        val finish = choice.optString("finish_reason")
                        if (finish.isNotBlank() && finish != "null") {
                            require(finish == "stop") {
                                "Cloudflare website output incomplete [format: output_limit_or_stop]; no files changed"
                            }
                            completed = true
                        }
                        choice.optJSONObject("delta")?.opt("content")
                    } else body.opt("response")
                    if (piece != null && piece != JSONObject.NULL) {
                        // Whitespace is meaningful inside JSON strings and often arrives as
                        // its own SSE delta. Blank chunks are valid, unlike a blank FINAL reply.
                        val chunk = if (piece is String) piece else visibleText(piece)
                            ?: throw IllegalArgumentException("Cloudflare website stream has unsupported text; no files changed")
                        text.append(chunk)
                        require(text.length <= 30_000) { "Cloudflare website output oversized; no files changed" }
                    }
                }
            } catch (e: java.io.IOException) {
                throw IllegalStateException("Cloudflare website stream interrupted or stalled; outcome uncertain. No files changed or automatic resend.")
            }
            require(done && (!sawChoice || completed) && text.isNotBlank()) {
                "Cloudflare website stream ended before a complete reply; no files changed or automatic resend"
            }
            WorkspaceWebsiteGeneration.parse(text.toString())
        }
    }
}
