package com.myra.assistant.ui.workspace

import org.json.JSONObject
import java.util.Locale

/** Safe, bounded Groq HTTP-400 category only. Never return raw provider messages:
 * they can echo user source, secrets or failed model generations.
 */
internal object WorkspaceWebsiteProviderError {
    fun category(body: String): String {
        if (body.length !in 1..8192) return "reason unavailable"
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            ?: return "reason unavailable"
        val code = error.optString("code").lowercase(Locale.ROOT)
        val message = error.optString("message").lowercase(Locale.ROOT)
        return when {
            "json_validate_failed" in code ||
                "generated json does not match" in message ||
                ("json" in message && "validation" in message) ->
                "generated JSON rejected by provider"
            "response_format" in message || "json_schema" in message ||
                "json_object" in message -> "response format rejected"
            "max_completion_tokens" in message || "max_tokens" in message ->
                "output token setting rejected"
            "context" in message && ("length" in message || "large" in message) ->
                "context too large"
            "model" in message && ("not found" in message || "unsupported" in message) ->
                "model rejected"
            else -> "reason unavailable"
        }
    }
}
