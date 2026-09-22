package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** Direct Z.ai, exact zero-list-price model allowlist; never uses Gemini Live or another gateway. */
internal object WorkspaceZaiFree {
    const val ENDPOINT = "https://api.z.ai/api/paas/v4/chat/completions"
    const val PREFERENCE_KEY = "workspace_zai_free_text_opt_in"
    const val VISION_PREFERENCE_KEY = "workspace_zai_free_vision_opt_in"
    const val MODEL_PREFERENCE_KEY = "workspace_zai_free_text_model"
    const val DEFAULT_TEXT_MODEL = "glm-4.7-flash"
    const val ALT_TEXT_MODEL = "glm-4.5-flash"
    const val VISION_MODEL = "glm-4.6v-flash"
    private const val MAX_RESPONSE_BYTES = 32_768L

    // No existing cross-provider, memory, or retry interceptors are attached.
    val client: OkHttpClient = WorkspaceFreeAiSuggestion.client.newBuilder().build()

    fun textModel(saved: String?): String = when (saved) {
        null, DEFAULT_TEXT_MODEL -> DEFAULT_TEXT_MODEL
        ALT_TEXT_MODEL -> ALT_TEXT_MODEL
        else -> throw IllegalArgumentException("Unrecognized Z.ai free model; nothing was sent")
    }

    fun request(key: String, messages: List<WorkspaceConversationStore.Message>,
                image: WorkspaceChatGateway.Image?, textModel: String,
                visionApproved: Boolean): Request {
        require(key.isNotBlank() && key.length <= 256 && key.none(Char::isWhitespace)) {
            "Save a valid Z.ai key in API & Cloud Settings"
        }
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
        // Reuse the existing same-chat system discipline and bounded raw-turn projection.
        // Remove OpenRouter-specific routing and plugin fields before contacting Z.ai.
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(messages, image))
        body.remove("provider")
        body.remove("plugins")
        body.put("model", model)
        return Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

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
}
