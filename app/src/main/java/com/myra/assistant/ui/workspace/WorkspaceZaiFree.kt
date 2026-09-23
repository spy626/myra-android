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

/** Direct Z.ai, exact zero-list-price model allowlist; never uses Gemini Live or another gateway. */
internal object WorkspaceZaiFree {
    const val ENDPOINT = "https://api.z.ai/api/paas/v4/chat/completions"
    const val PREFERENCE_KEY = "workspace_zai_free_text_opt_in"
    const val VISION_PREFERENCE_KEY = "workspace_zai_free_vision_opt_in"
    const val CODING_PREFERENCE_KEY = "workspace_zai_free_coding_source_opt_in"
    const val MODEL_PREFERENCE_KEY = "workspace_zai_free_text_model"
    const val DEFAULT_TEXT_MODEL = "glm-4.7-flash"
    const val ALT_TEXT_MODEL = "glm-4.5-flash"
    const val VISION_MODEL = "glm-4.6v-flash"
    private const val MAX_RESPONSE_BYTES = 32_768L
    private const val MAX_CODING_PROMPT_CHARS = 12_000

    // No existing cross-provider, memory, or retry interceptors are attached.
    val client: OkHttpClient = WorkspaceFreeAiSuggestion.client.newBuilder().build()

    // Website generation needs longer server-thinking/read time than ordinary chat.
    // Keep the whole operation bounded to 80s, but do not inherit OkHttp's ~10s read
    // timeout from the base client. Still one request only: no retry/fallback interceptor
    // and no saved-memory interceptor.
    val websiteClient: OkHttpClient = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .callTimeout(80, TimeUnit.SECONDS)
        .build()

    fun validKey(key: String): Boolean =
        key.isNotBlank() && key.length <= 256 && key.none(Char::isWhitespace)

    fun textModel(saved: String?): String = when (saved) {
        null, DEFAULT_TEXT_MODEL -> DEFAULT_TEXT_MODEL
        ALT_TEXT_MODEL -> ALT_TEXT_MODEL
        else -> throw IllegalArgumentException("Unrecognized Z.ai free model; nothing was sent")
    }

    fun displayName(model: String): String = when (textModel(model)) {
        DEFAULT_TEXT_MODEL -> "GLM-4.7-Flash"
        ALT_TEXT_MODEL -> "GLM-4.5-Flash"
        else -> error("unreachable")
    }

    private fun requireKey(key: String) {
        require(validKey(key)) { "Save a valid Z.ai key in API & Cloud Settings" }
    }

    private fun requireCodingConsent(approved: Boolean) {
        require(approved) { "Z.ai Work coding source permission is OFF; no project source was sent" }
    }

    fun request(key: String, messages: List<WorkspaceConversationStore.Message>,
                image: WorkspaceChatGateway.Image?, textModel: String,
                visionApproved: Boolean): Request {
        requireKey(key)
        require(messages.isNotEmpty() && messages.last().role == "user" &&
            WorkspaceLongInputPolicy.requestFits(messages)) { "Selected chat exceeds safe request limit" }
        val model = if (image == null) WorkspaceZaiFree.textModel(textModel) else {
            require(visionApproved) { "Z.ai vision needs its separate permission; no image sent" }
            require(image.mime == "image/jpeg" || image.mime == "image/png") { "Unsupported photo format" }
            require(image.base64.length in 1..2_700_000 &&
                image.base64.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) {
                "Photo is invalid or too large"
            }
            VISION_MODEL
        }
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(messages, image))
        body.remove("provider")
        body.remove("plugins")
        body.put("model", model)
        return directRequest(key, body)
    }

    /** Explicit single-file coding request. The caller must have separate project-source consent. */
    fun editRequest(key: String, prompt: String, textModel: String,
                    sourceApproved: Boolean): Request {
        requireKey(key)
        requireCodingConsent(sourceApproved)
        require(prompt.isNotBlank() && prompt.length <= MAX_CODING_PROMPT_CHARS) {
            "Selected coding source exceeds safe Z.ai request limit"
        }
        val payload = JSONObject()
            .put("model", WorkspaceZaiFree.textModel(textModel))
            .put("stream", false)
            .put("max_tokens", WorkspaceFreeAiSuggestion.MAX_OUTPUT_TOKENS)
            .put("temperature", 0.2)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt)
            ))
        return directRequest(key, payload)
    }

    /** Reuse the canonical website brief/snapshot owner; only transport changes to direct Z.ai. */
    fun websiteRequest(key: String, snapshot: WorkspaceWebsiteGeneration.Snapshot,
                       textModel: String, sourceApproved: Boolean): Request {
        requireKey(key)
        requireCodingConsent(sourceApproved)
        val canonical = WorkspaceWebsiteGeneration.request("local-body-only", snapshot)
        val buffer = Buffer()
        requireNotNull(canonical.body).writeTo(buffer)
        val payload = JSONObject(buffer.readUtf8())
        payload.remove("provider")
        payload.remove("plugins")
        payload.put("model", WorkspaceZaiFree.textModel(textModel))
        return directRequest(key, payload)
    }

    private fun directRequest(key: String, body: JSONObject): Request =
        Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

    fun readEdit(response: Response): String = read(response)

    fun read(response: Response): String = response.use {
        require(it.isSuccessful) {
            when (it.code) {
                401, 403 -> "Z.ai refused the API key or free model access (HTTP ${it.code}). No paid fallback."
                402 -> "Z.ai requested payment (HTTP 402); LYRA stopped. No paid fallback."
                429 -> "Z.ai free model is rate-limited (HTTP 429); try later. No automatic retry."
                else -> "Z.ai free request failed (HTTP ${it.code}). No paid fallback."
            }
        }
        val bytes = it.peekBody(MAX_RESPONSE_BYTES + 1).bytes()
        require(bytes.isNotEmpty() && bytes.size <= MAX_RESPONSE_BYTES) {
            "Z.ai response is empty or exceeds safe size"
        }
        val root = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("Z.ai returned invalid response") }
        require(!root.has("error")) { "Z.ai returned an error; no paid fallback" }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IllegalArgumentException("Z.ai did not return a complete reply")
        require(choice.optString("finish_reason") == "stop") {
            "Z.ai reply was incomplete or filtered; no partial reply saved"
        }
        val content = choice.optJSONObject("message")?.opt("content")
        require(content is String && content.trim().length in 1..6_000) {
            "Z.ai returned no bounded text reply"
        }
        content.trim()
    }

    /** Fixed categories only: never echo provider bodies, keys, prompts or source. */
    internal fun websiteHttpFailure(code: Int): String = when (code) {
        400 -> "Z.ai Free HTTP 400: website request or output format rejected; no automatic retry or paid fallback. Project files unchanged."
        401, 403 -> "Z.ai Free HTTP $code: key or model access refused; no project files changed."
        402 -> "Z.ai Free HTTP 402 requested payment; LYRA stopped. No paid fallback; project files unchanged."
        408 -> "Z.ai Free HTTP 408: upstream request timed out; no uncertain resend. Project files unchanged."
        429 -> "Z.ai Free HTTP 429: rate-limited; try later. No automatic retry or paid fallback; project files unchanged."
        502, 503, 504 -> "Z.ai Free HTTP $code: service unavailable; no cross-provider resend. Project files unchanged."
        else -> "Z.ai Free HTTP $code: website request refused; no paid fallback. Project files unchanged."
    }
}
