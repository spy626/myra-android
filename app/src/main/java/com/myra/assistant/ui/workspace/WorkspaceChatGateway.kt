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
        .addInterceptor(WorkspaceMemoryInterceptor()) // read-only, explicit opt-in, off UI thread
        .addInterceptor(WorkspaceFreeRouteRetry())
        .build()

    /** Each request includes only bounded messages from the explicitly selected project. */
    fun request(provider: Provider, key: String, messages: List<WorkspaceConversationStore.Message>,
                image: Image? = null): Request {
        require(key.isNotBlank() && key.length <= 256 && key.none(Char::isWhitespace)) {
            "Set a valid OpenRouter key in API & Cloud Settings"
        }
        require(messages.isNotEmpty() && messages.last().role == "user") { "A user message is required" }
        // Previous turns are dropped whole when needed; the latest pasted prompt is never sliced.
        require(WorkspaceLongInputPolicy.requestFits(messages)) {
            "Full prompt exceeds this free route's 64000-character request cap; saved locally, nothing sent"
        }
        image?.let {
            require(it.mime == "image/jpeg" || it.mime == "image/png") { "Unsupported photo format" }
            require(it.base64.length in 1..2_700_000 &&
                it.base64.all { char -> char.isLetterOrDigit() || char == '+' || char == '/' || char == '=' }) {
                "Photo is invalid or exceeds the request limit"
            }
        }
        // Inspect earlier user intent locally when needed, but transmit only recent raw turns.
        val body = when (provider) {
            Provider.OPENROUTER_FREE -> openRouterBody(messages, image)
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
        val recent = WorkspaceLongInputPolicy.outbound(messages)
        val latest = recent.lastOrNull()?.takeIf { it.role == "user" }?.text
        val revisionKind = WorkspacePromptFollowUp.kind(messages)
        val contextDecision = WorkspacePromptContext.resolve(messages)
        val writingInstructions = when {
            revisionKind != null -> WorkspacePromptFollowUp.instructions(revisionKind)
            contextDecision != null -> WorkspacePromptContext.instructions(contextDecision)
            latest != null && WorkspacePromptWriting.kind(latest) != null ->
                WorkspacePromptWriting.instructions(latest)
            latest != null && WorkspaceStoryScript.isWritingRequest(latest) ->
                WorkspaceStoryScript.writingInstructions(latest)
            else -> ""
        }
        // The selected chat's older USER statements may help a follow-up, but never
        // import other chats or assistant guesses. The existing intent projection already
        // covers ambiguous prompt decisions, so do not duplicate its earlier evidence.
        val earlier = if (revisionKind == null && contextDecision == null)
            WorkspaceContextProjection.earlierUserContext(messages) else ""
        val instructions = listOf(writingInstructions, earlier).filter(String::isNotBlank)
            .joinToString("\n\n")
        if (instructions.isNotBlank()) entries.put(JSONObject().put("role", "system")
            .put("content", instructions))
        recent.forEachIndexed { index, message ->
            require(message.role == "user" || message.role == "assistant") { "Invalid chat role" }
            require(message.text.length in 1..WorkspaceConversationStore.MAX_MESSAGE_LENGTH) { "Invalid message size" }
            val content: Any = if (image != null && index == recent.lastIndex) {
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
