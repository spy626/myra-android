package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Explicit manual text-chat adapter for one validated Custom Provider profile.
 *
 * No attachments, project source, saved LYRA memory or automatic cross-provider fallback.
 * Reuses the existing bounded same-chat LYRA prompt projection.
 */
internal object WorkspaceCustomProviderChat {
    private const val MAX_RESPONSE_BYTES = 32_768L
    private const val MAX_REPLY_CHARS = 6_000

    fun request(
        profile: WorkspaceCustomProviderProfile.Validated,
        apiKey: String,
        messages: List<WorkspaceConversationStore.Message>,
        extraSystemInstructions: String? = null,
    ): Request {
        require(WorkspaceProviderRegistry.TaskKind.CHAT_TEXT in profile.tasks) {
            "Custom provider is not configured for text chat"
        }
        require(!profile.attachmentsAllowed) {
            "Custom provider attachments are not enabled in this LYRA phase"
        }
        require(messages.isNotEmpty() && messages.last().role == "user") {
            "A user message is required"
        }
        require(WorkspaceLongInputPolicy.requestFits(messages)) {
            "Full message exceeds LYRA's local chat cap; saved locally, nothing sent"
        }
        val recent = WorkspaceLongInputPolicy.outbound(messages)
        require(recent.sumOf { it.text.length } <= profile.maxPromptChars) {
            "Selected Custom provider prompt budget is too small for this chat; nothing sent"
        }
        require(apiKey.length <= 512 && apiKey.none { it == '\n' || it == '\r' }) {
            "Custom API key is too long or contains line breaks"
        }

        val body = JSONObject()
            .put("model", profile.modelId)
            .put("stream", false)
            .put("max_tokens", profile.maxOutputTokens)
            .put("temperature", 0.2)
            .put("messages", WorkspaceChatGateway.openAiMessages(
                messages, extraSystemInstructions = extraSystemInstructions))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val builder = Request.Builder()
            .url(profile.chatCompletionsUrl)
            .header("Content-Type", "application/json")
            .post(body)
        if (apiKey.isNotBlank()) {
            when (profile.authMode) {
                WorkspaceCustomProviderProfile.AuthMode.BEARER ->
                    builder.header("Authorization", "Bearer $apiKey")
                WorkspaceCustomProviderProfile.AuthMode.X_API_KEY ->
                    builder.header("X-API-Key", apiKey)
            }
        }
        return builder.build()
    }

    fun read(response: Response): String {
        response.use {
            require(it.isSuccessful) {
                when (it.code) {
                    401, 403 -> "Custom provider refused the API key or access."
                    402 -> "Custom provider requested payment. LYRA will not attempt payment or fallback."
                    404 -> "Custom provider chat/completions endpoint was not found."
                    408, 504 -> "Custom provider timed out. No retry or fallback was sent."
                    429 -> "Custom provider is rate-limited. No automatic fallback was sent."
                    in 300..399 -> "Custom provider redirect was refused for credential safety."
                    in 500..599 -> "Custom provider is temporarily unavailable. No automatic fallback was sent."
                    else -> "Custom provider rejected the request (HTTP ${it.code})."
                }
            }
            val peek = it.peekBody(MAX_RESPONSE_BYTES + 1)
            val bytes = peek.bytes()
            require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_RESPONSE_BYTES) {
                "Custom provider reply was empty or too large"
            }
            val root = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
                .getOrElse { throw IllegalArgumentException(
                    "Custom provider did not return valid OpenAI-compatible JSON") }
            require(!root.has("error")) { "Custom provider returned an error object" }
            val choice = root.optJSONArray("choices")?.optJSONObject(0)
                ?: throw IllegalArgumentException("Custom provider returned no reply choice")
            when (choice.optString("finish_reason")) {
                "", "stop" -> Unit
                "length" -> throw IllegalArgumentException(
                    "Custom provider reached its output limit; incomplete reply not saved")
                "content_filter" -> throw IllegalArgumentException(
                    "Custom provider filtered the reply; no reply saved")
                else -> throw IllegalArgumentException(
                    "Custom provider stopped without a complete text reply")
            }
            val content = choice.optJSONObject("message")?.opt("content")
            require(content is String && content.trim().length in 1..MAX_REPLY_CHARS) {
                "Custom provider returned no usable bounded text reply"
            }
            return content.trim()
        }
    }

    fun networkFailure(error: IOException): String = when (error) {
        is SocketTimeoutException, is InterruptedIOException ->
            "Custom provider request timed out. No retry or automatic fallback was sent."
        else ->
            "Custom provider connection failed before a usable response. No retry or automatic fallback was sent."
    }
}
