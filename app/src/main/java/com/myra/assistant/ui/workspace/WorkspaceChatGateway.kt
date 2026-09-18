package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** Workspace text and explicitly selected one-turn images. Never uses the voice-only Gemini key. */
internal object WorkspaceChatGateway {
    enum class Provider { OPENROUTER_FREE }
    data class Image(val mime: String, val base64: String)
    private const val MAX_REPLY_BYTES = 32_768L
    // One extra try only after specific upstream HTTP rejections. Connection failures and
    // ambiguous timeouts are NOT retried. Retain the existing 35-second total call timeout.
    val client: OkHttpClient = WorkspaceFreeAiSuggestion.client.newBuilder()
        .addInterceptor(WorkspaceFreeRouteRetry())
        .build()

    /** Each request includes only bounded messages from the explicitly selected project. */
    fun request(provider: Provider, key: String, messages: List<WorkspaceConversationStore.Message>,
                image: Image? = null): Request {
        require(key.isNotBlank() && key.length <= 256 && key.none(Char::isWhitespace)) {
            "Set a valid OpenRouter key in API & Cloud Settings"
        }
        require(messages.isNotEmpty() && messages.last().role == "user") { "A user message is required" }
        val recent = messages.takeLast(8)
        require(recent.sumOf { it.text.length } <= 12_000) { "Conversation is too long for one private request" }
        image?.let {
            require(it.mime == "image/jpeg" || it.mime == "image/png") { "Unsupported photo format" }
            require(it.base64.length in 1..2_700_000 &&
                it.base64.all { char -> char.isLetterOrDigit() || char == '+' || char == '/' || char == '=' }) {
                "Photo is invalid or exceeds the request limit"
            }
        }
        val body = when (provider) {
            Provider.OPENROUTER_FREE -> openRouterBody(recent, image)
        }.toRequestBody("application/json; charset=utf-8".toMediaType())
        return Request.Builder()
            .url(WorkspaceFreeAiSuggestion.ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
    }

    fun openRouterBody(messages: List<WorkspaceConversationStore.Message>, image: Image? = null): String {
        val entries = JSONArray()
        messages.forEachIndexed { index, message ->
            require(message.role == "user" || message.role == "assistant") { "Invalid chat role" }
            require(message.text.length in 1..WorkspaceConversationStore.MAX_MESSAGE_LENGTH) { "Invalid message size" }
            val content: Any = if (image != null && index == messages.lastIndex) {
                JSONArray().put(JSONObject().put("type", "text").put("text", message.text))
                    .put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:${image.mime};base64,${image.base64}")))
            } else message.text
            entries.put(JSONObject().put("role", message.role).put("content", content))
        }
        return JSONObject().put("model", WorkspaceFreeAiSuggestion.MODEL)
            .put("stream", false).put("max_tokens", 2_048)
            .put("provider", JSONObject().put("zdr", true).put("data_collection", "deny")
                .put("allow_fallbacks", false))
            .put("messages", entries).toString()
    }

    fun read(provider: Provider, response: Response): String = when (provider) {
        Provider.OPENROUTER_FREE -> WorkspaceFreeAiSuggestion.readResponse(response)
    }
}
