package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject

/** One explicitly authorized, bounded OpenRouter 429 -> Groq Free WEBSITE resend.
 * Chat consent does not cover project source. No other error or a Groq failure retries.
 * Groq cannot enforce an API-side $0 cap; user must keep their Groq account Free.
 */
internal object WorkspaceWebsiteGroqFallback {
    const val PREFERENCE_KEY = "workspace_website_groq_429_opt_in"
    private const val MAX_PROMPT_CHARS = 12_000
    private const val MAX_COMPLETION_TOKENS = 4_500

    fun eligible(code: Int, websiteOptIn: Boolean, groqFreeZdrOptIn: Boolean,
                 groqKey: String): Boolean = code == 429 && websiteOptIn && groqFreeZdrOptIn &&
        groqKey.isNotBlank() && groqKey.length <= 256 && groqKey.none(Char::isWhitespace)

    /** Reuse the exact approved website goal and selected three-file snapshot. Never send
     * chat history, memory, file attachments, secrets or unrelated project directories.
     */
    fun request(groqKey: String, snapshot: WorkspaceWebsiteGeneration.Snapshot): Request {
        require(groqKey.isNotBlank() && groqKey.length <= 256 && groqKey.none(Char::isWhitespace)) {
            "A valid Groq Free key is required"
        }
        // The placeholder is only used to construct the existing, already reviewed request body.
        // Its Authorization header is not used or sent to either provider.
        val sourceRequest = WorkspaceWebsiteGeneration.request("local-body-only", snapshot)
        val buffer = Buffer()
        requireNotNull(sourceRequest.body).writeTo(buffer)
        val payload = JSONObject(buffer.readUtf8())
        val messages = payload.getJSONArray("messages")
        val chars = (0 until messages.length()).sumOf {
            messages.getJSONObject(it).getString("content").length
        }
        require(chars <= MAX_PROMPT_CHARS) {
            "Website source is too large for a conservative Groq Free request; existing files unchanged"
        }
        payload.remove("provider")
        payload.remove("plugins")
        payload.remove("max_tokens")
        payload.put("model", WorkspaceGroqFree.MODEL)
            .put("max_completion_tokens", MAX_COMPLETION_TOKENS)
            .put("reasoning_effort", "low")
            .put("reasoning_format", "hidden")
            .put("response_format", JSONObject().put("type", "json_object"))
        return Request.Builder().url(WorkspaceGroqFree.ENDPOINT)
            .header("Authorization", "Bearer $groqKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }
}
