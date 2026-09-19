package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** Text-only Groq Free candidate. The user must opt in on a Free account with inference ZDR.
 * Groq has no API-side hard $0 price ceiling: disabling this route if the account is upgraded
 * remains necessary. No tool calls, Gemini key, provider fallback, personal memory or file edits.
 */
internal object WorkspaceGroqFree {
    const val PREFERENCE_KEY = "workspace_groq_free_zdr_opt_in"
    const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    const val MODEL = "openai/gpt-oss-120b"
    // This is a conservative local budget, NOT the model's native 131K context or an exact
    // token count. Free account's token-per-minute quota can be much smaller than native context.
    const val MAX_PROMPT_CHARS = 12_000
    private const val MAX_RESPONSE_BYTES = 96_000L

    fun body(messages: List<WorkspaceConversationStore.Message>, image: WorkspaceChatGateway.Image? = null): String {
        require(image == null) { "Groq Free text route does not accept photos; nothing was sent" }
        val recent = WorkspaceLongInputPolicy.outbound(messages)
        require(recent.isNotEmpty() && recent.last().role == "user") { "Latest user message is required" }
        require(recent.sumOf { it.text.length } <= MAX_PROMPT_CHARS) {
            "Groq Free request exceeds LYRA's conservative free-quota budget; complete prompt saved locally, nothing sent. Use OpenRouter Free for a larger request."
        }
        val json = JSONObject(WorkspaceChatGateway.openRouterBody(messages))
        json.put("model", MODEL)
        json.remove("provider") // OpenRouter-specific settings must never leak to Groq.
        json.remove("plugins")
        json.remove("max_tokens")
        json.put("max_completion_tokens", 2_048)
        return json.toString()
    }

    fun request(key: String, messages: List<WorkspaceConversationStore.Message>,
                image: WorkspaceChatGateway.Image? = null): Request {
        require(key.isNotBlank() && key.length <= 256 && key.none(Char::isWhitespace)) {
            "A valid Groq key is required in API & Cloud Settings"
        }
        val payload = body(messages, image).toRequestBody("application/json; charset=utf-8".toMediaType())
        return Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(payload).build()
    }

    fun read(response: Response): String = response.use { result ->
        if (!result.isSuccessful) {
            val category = when (result.code) {
                401, 403 -> "key or account access refused"
                402 -> "payment required; nothing paid"
                413 -> "request too large; full message saved locally"
                429 -> "Free account rate-limited; try again after quota reset"
                503, 502, 504 -> "service temporarily unavailable"
                else -> "request refused"
            }
            throw IllegalArgumentException("Groq HTTP ${result.code}: $category. No paid fallback.")
        }
        val data = requireNotNull(result.peekBody(MAX_RESPONSE_BYTES + 1)).bytes()
        require(data.isNotEmpty() && data.size.toLong() <= MAX_RESPONSE_BYTES) {
            "Groq response missing or too large; no incomplete reply saved"
        }
        val json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("Groq returned an invalid reply") }
        require(!json.has("error")) { "Groq returned an error; no reply saved" }
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        require(choice != null) { "Groq returned no reply" }
        when (choice.optString("finish_reason")) {
            "stop" -> Unit
            "length" -> throw IllegalArgumentException("Groq reached its output-token limit; incomplete reply not saved")
            "content_filter" -> throw IllegalArgumentException("Groq filtered the reply")
            else -> throw IllegalArgumentException("Groq did not finish its reply")
        }
        val answer = choice.optJSONObject("message")?.opt("content")
        require(answer is String && answer.trim().length in 1..WorkspaceConversationStore.MAX_MESSAGE_LENGTH) {
            "Groq returned no complete bounded text reply"
        }
        answer.trim()
    }
}
