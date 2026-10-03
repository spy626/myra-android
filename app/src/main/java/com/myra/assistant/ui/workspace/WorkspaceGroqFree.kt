package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** Text-only Groq Free candidate. The user must opt in on a Free account with inference ZDR.
 * Groq has no API-side hard $0 price ceiling: disabling this route if the account is upgraded
 * remains necessary. No tool calls, Gemini key, personal memory or file edits.
 */
internal object WorkspaceGroqFree {
    const val PREFERENCE_KEY = "workspace_groq_free_zdr_opt_in"
    const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    const val MODEL = "openai/gpt-oss-120b"
    // This is a conservative local budget, NOT the model's native 131K context or an exact
    // token count. Free account's token-per-minute quota can be much smaller than native context.
    const val MAX_PROMPT_CHARS = 12_000
    private const val MAX_RESPONSE_BYTES = 96_000L

    /**
     * One immutable projection for preflight and HTTP body. When a long chat
     * exceeds Groq Free's conservative 12k-character local ceiling, first use
     * the existing compact planning prompt (if applicable), then drop only
     * whole OLD conversation turns in the outbound request copy. Never slice
     * the latest user turn, mutate the conversation store, omit approved
     * runtime instructions or invoke another provider.
     */
    private fun projected(
        messages: List<WorkspaceConversationStore.Message>,
        extraSystemInstructions: String?,
    ): org.json.JSONArray {
        var window = WorkspaceLongInputPolicy.outbound(messages)
        while (true) {
            val regular = WorkspaceChatGateway.openAiMessages(
                window, extraSystemInstructions = extraSystemInstructions,
            )
            if (length(regular) <= MAX_PROMPT_CHARS) return regular

            val latest = window.last().text
            val compact = if (WorkspacePracticalPlanningGuide.instructions(latest).isNotBlank())
                WorkspaceChatGateway.openAiMessages(
                    window, extraSystemInstructions = extraSystemInstructions,
                    compactForGroq = true,
                ) else null
            val best = if (compact != null && length(compact) < length(regular))
                compact else regular
            if (length(best) <= MAX_PROMPT_CHARS || window.size == 1) return best

            // Discard an oldest prior turn, never partial text. Avoid an orphan
            // preceding assistant turn when the corresponding user turn goes.
            window = window.drop(1)
            if (window.size > 1 && window.first().role == "assistant")
                window = window.drop(1)
        }
    }

    private fun length(entries: org.json.JSONArray): Int =
        (0 until entries.length()).sumOf { index ->
            (entries.getJSONObject(index).opt("content") as? String)?.length
                ?: (MAX_PROMPT_CHARS + 1)
        }

    internal fun promptChars(
        messages: List<WorkspaceConversationStore.Message>,
        extraSystemInstructions: String? = null,
    ): Int? = runCatching { length(projected(messages, extraSystemInstructions)) }.getOrNull()

    internal fun withinBudget(
        messages: List<WorkspaceConversationStore.Message>,
        extraSystemInstructions: String? = null,
    ): Boolean = promptChars(messages, extraSystemInstructions)
        ?.let { it <= MAX_PROMPT_CHARS } ?: false

    fun body(
        messages: List<WorkspaceConversationStore.Message>,
        image: WorkspaceChatGateway.Image? = null,
        extraSystemInstructions: String? = null,
    ): String {
        require(image == null) { "Groq Free text route does not accept photos; nothing was sent" }
        val entries = projected(messages, extraSystemInstructions)
        require(length(entries) <= MAX_PROMPT_CHARS) {
            "Groq Free prompt exceeds LYRA's conservative Free-route budget; newest user message stays saved locally and nothing was sent. Shorten old chat context or use an approved Free route."
        }
        // Groq must never receive OpenRouter-only provider settings or paid fallback.
        return JSONObject()
            .put("model", MODEL)
            .put("stream", false)
            .put("messages", entries)
            .put("max_completion_tokens", 2_048)
            .toString()
    }

    fun request(
        key: String,
        messages: List<WorkspaceConversationStore.Message>,
        image: WorkspaceChatGateway.Image? = null,
        extraSystemInstructions: String? = null,
    ): Request {
        require(key.isNotBlank() && key.length <= 256 && key.none(Char::isWhitespace)) {
            "A valid Groq key is required in API & Cloud Settings"
        }
        val payload = body(messages, image, extraSystemInstructions)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        return Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(payload).build()
    }

    fun read(response: Response): String {
        // The existing callback starts with Groq, but a consented HTTP failover can finish
        // on OpenRouter. Use the actual request URL, never assume the original provider.
        if (response.request.url.toString() == WorkspaceFreeAiSuggestion.ENDPOINT)
            return WorkspaceFreeAiSuggestion.readResponse(response)
        return response.use { result ->
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
}
