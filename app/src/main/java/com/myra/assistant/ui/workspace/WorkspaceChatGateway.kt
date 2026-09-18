package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** Workspace text and explicitly consented one-turn images. No automatic upload, retry or model switch. */
internal object WorkspaceChatGateway {
    enum class Provider { OPENROUTER_FREE, GEMINI_FREE_TIER }
    data class Image(val mime: String, val base64: String)
    private const val GEMINI_MODEL = "gemini-2.5-flash"
    private const val GEMINI_ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
    private const val MAX_REPLY_BYTES = 32_768L
    val client: OkHttpClient = WorkspaceFreeAiSuggestion.client

    /** Each request includes only bounded messages from the explicitly selected project. */
    fun request(provider: Provider, key: String, messages: List<WorkspaceConversationStore.Message>,
                image: Image? = null): Request {
        require(key.isNotBlank() && key.length <= 256 && key.none(Char::isWhitespace)) {
            "Set a valid provider key in API & Cloud Settings"
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
            Provider.GEMINI_FREE_TIER -> geminiBody(recent, image)
        }.toRequestBody("application/json; charset=utf-8".toMediaType())
        return Request.Builder()
            .url(if (provider == Provider.OPENROUTER_FREE) WorkspaceFreeAiSuggestion.ENDPOINT else GEMINI_ENDPOINT)
            .header(if (provider == Provider.OPENROUTER_FREE) "Authorization" else "x-goog-api-key",
                if (provider == Provider.OPENROUTER_FREE) "Bearer $key" else key)
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

    fun geminiBody(messages: List<WorkspaceConversationStore.Message>, image: Image? = null): String {
        val entries = JSONArray()
        messages.forEachIndexed { index, message ->
            require(message.role == "user" || message.role == "assistant") { "Invalid chat role" }
            require(message.text.length in 1..WorkspaceConversationStore.MAX_MESSAGE_LENGTH) { "Invalid message size" }
            val parts = JSONArray().put(JSONObject().put("text", message.text))
            if (image != null && index == messages.lastIndex) {
                parts.put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", image.mime).put("data", image.base64)))
            }
            entries.put(JSONObject().put("role", if (message.role == "assistant") "model" else "user")
                .put("parts", parts))
        }
        return JSONObject().put("contents", entries)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 2_048)).toString()
    }

    fun read(provider: Provider, response: Response): String {
        if (provider == Provider.OPENROUTER_FREE) return WorkspaceFreeAiSuggestion.readResponse(response)
        response.use {
            require(it.isSuccessful) {
                when (it.code) {
                    401, 403 -> "Gemini key or model access refused. Check API & Cloud Settings."
                    402 -> "Gemini requires billing for this account; no paid request will be retried."
                    404 -> "Gemini model is unavailable for this key or region. No fallback."
                    408, 429 -> "Gemini free-tier limit or timeout reached. No paid fallback."
                    else -> "Gemini request failed (HTTP ${it.code}); no retry or fallback."
                }
            }
            val bytes = it.peekBody(MAX_REPLY_BYTES + 1).bytes()
            require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_REPLY_BYTES) {
                "Gemini response missing or too large"
            }
            return parseGemini(String(bytes, Charsets.UTF_8))
        }
    }

    fun parseGemini(raw: String): String {
        require(raw.length in 1..32_768) { "Gemini returned an invalid response" }
        val root = runCatching { JSONObject(raw) }
            .getOrElse { throw IllegalArgumentException("Gemini returned invalid JSON") }
        require(!root.has("error")) { "Gemini refused the request; check free-tier access" }
        val first = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw IllegalArgumentException("Gemini returned no answer")
        require(first.optString("finishReason") == "STOP") { "Gemini reply was incomplete or filtered" }
        val parts = first.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalArgumentException("Gemini returned no text")
        val result = buildString {
            for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }.trim()
        require(result.length in 1..WorkspaceConversationStore.MAX_MESSAGE_LENGTH) {
            "Gemini response is empty or exceeds the local limit"
        }
        return result
    }
}
