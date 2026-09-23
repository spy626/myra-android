package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * Text-only LLM7 Free candidate for ordinary Workspace Chat.
 * The user must explicitly opt in once in Settings. No voice, attachments,
 * project source, saved LYRA memory, coding writes or paid fallback.
 *
 * LLM7's "default" route may select different upstream models over time, so
 * this provider must not be treated as a stable model identity.
 */
internal object WorkspaceLlm7Free {
    const val PREFERENCE_KEY = "workspace_llm7_free_text_opt_in"
    const val ENDPOINT = "https://api.llm7.io/v1/chat/completions"
    const val MODEL = "default"
    const val MAX_PROMPT_CHARS = 16_000
    private const val MAX_RESPONSE_BYTES = 96_000L

    // Dedicated client: do not inherit OpenRouter memory injection or cross-provider retry.
    val client: OkHttpClient = WorkspaceFreeAiSuggestion.client.newBuilder().build()

    fun validKey(key: String): Boolean =
        key.isNotBlank() && key.length <= 256 && key.none(Char::isWhitespace)

    internal fun withinBudget(messages: List<WorkspaceConversationStore.Message>): Boolean =
        runCatching {
            val json = JSONObject(WorkspaceChatGateway.openRouterBody(messages))
            val entries = json.getJSONArray("messages")
            (0 until entries.length()).sumOf { i ->
                (entries.getJSONObject(i).opt("content") as? String)?.length
                    ?: (MAX_PROMPT_CHARS + 1)
            } <= MAX_PROMPT_CHARS
        }.getOrDefault(false)

    fun body(messages: List<WorkspaceConversationStore.Message>,
             image: WorkspaceChatGateway.Image? = null): String {
        require(image == null) { "LLM7 Free is text-only in LYRA; attachment was not sent" }
        val json = JSONObject(WorkspaceChatGateway.openRouterBody(messages))
        require(withinBudget(messages)) {
            "LLM7 Free request exceeds LYRA's conservative free-route budget; full message saved locally, nothing sent"
        }
        json.put("model", MODEL)
        json.put("stream", false)
        json.put("max_tokens", 2_048)
        json.remove("provider")
        json.remove("plugins")
        json.remove("response_format")
        json.remove("tools")
        return json.toString()
    }

    fun request(key: String, messages: List<WorkspaceConversationStore.Message>,
                image: WorkspaceChatGateway.Image? = null): Request {
        require(validKey(key)) { "Set a valid LLM7 free token in API & Cloud Settings" }
        val payload = body(messages, image)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        return Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(payload).build()
    }

    fun read(response: Response): String = response.use { result ->
        if (!result.isSuccessful) {
            val category = when (result.code) {
                401, 403 -> "free token or account access refused"
                402 -> "payment required; no paid request was made"
                413 -> "request too large; full message saved locally"
                429 -> "free quota or rate limit reached; try after reset"
                502, 503, 504 -> "service temporarily unavailable"
                else -> "request refused"
            }
            throw IllegalArgumentException("LLM7 HTTP ${result.code}: $category. No paid fallback.")
        }
        val data = result.peekBody(MAX_RESPONSE_BYTES + 1).bytes()
        require(data.isNotEmpty() && data.size.toLong() <= MAX_RESPONSE_BYTES) {
            "LLM7 response missing or too large; no incomplete reply saved"
        }
        val json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("LLM7 returned an invalid reply") }
        require(!json.has("error")) { "LLM7 returned an error; no reply saved" }
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        require(choice != null) { "LLM7 returned no reply" }
        when (choice.optString("finish_reason")) {
            "stop" -> Unit
            "length" -> throw IllegalArgumentException(
                "LLM7 reached its output-token limit; incomplete reply not saved")
            "content_filter" -> throw IllegalArgumentException("LLM7 filtered the reply")
            else -> throw IllegalArgumentException("LLM7 did not finish its reply")
        }
        val answer = choice.optJSONObject("message")?.opt("content")
        require(answer is String && answer.trim().length in 1..WorkspaceConversationStore.MAX_MESSAGE_LENGTH) {
            "LLM7 returned no complete bounded text reply"
        }
        answer.trim()
    }
}
