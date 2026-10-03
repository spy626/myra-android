package com.myra.assistant.ui.workspace

import android.content.Context
import com.myra.assistant.MyApplication
import com.myra.assistant.ai.ApiKeyStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

/**
 * An optional, explicit-consent boundary for ONE text-only Groq -> OpenRouter free fallback.
 * The existing provider selection remains the sole initial router. This policy never sends
 * project source, attachments, voice data or saved LYRA memory to another company.
 * An API key alone is NOT permission to switch providers.
 */
internal object WorkspaceFreeCrossProvider {
    const val PREFERENCE_KEY = "workspace_free_cross_provider_opt_in"
    private const val PREFERENCES = "workspace_ui"
    private const val MAX_BODY_BYTES = 128_000L

    /** Only a definite upstream rejection, not a timeout, a partial reply or a payment error. */
    internal fun eligibleStatus(code: Int): Boolean =
        WorkspaceProviderRegistry.definitiveFallbackAllowed(
            WorkspaceProviderRegistry.Id.GROQ_FREE, code)

    fun fallbackRequest(original: Request): Request? {
        // Avoid touching Android, encrypted keys or preferences for any other endpoint.
        if (original.method != "POST" || original.url.toString() != WorkspaceGroqFree.ENDPOINT) return null
        return runCatching {
            val context = MyApplication.contextOrNull() ?: return@runCatching null
            val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREFERENCE_KEY, false) ||
                !prefs.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false)) return@runCatching null
            if (!WorkspaceProviderSessionHealth.canSend(
                    WorkspaceProviderRegistry.Id.OPENROUTER_FREE)) return@runCatching null
            val key = ApiKeyStore(context).get(ApiKeyStore.OPENROUTER)
            openRouterRequest(original, key)
        }.getOrNull() // Secure-key or preference failures must fail closed.
    }

    /** Pure transformation for tests; never reconstructs, trims or summarizes the original text. */
    internal fun openRouterRequest(original: Request, openRouterKey: String): Request? = runCatching {
        if (original.method != "POST" || original.url.toString() != WorkspaceGroqFree.ENDPOINT ||
            openRouterKey.isBlank() || openRouterKey.length > 256 ||
            openRouterKey.any(Char::isWhitespace) || ',' in openRouterKey) return@runCatching null
        val body = original.body ?: return@runCatching null
        if (body.isOneShot() || body.isDuplex()) return@runCatching null
        val buffer = Buffer()
        body.writeTo(buffer)
        if (buffer.size !in 1..MAX_BODY_BYTES) return@runCatching null
        val json = JSONObject(buffer.readUtf8())
        if (json.optString("model") != WorkspaceGroqFree.MODEL ||
            json.optBoolean("stream", true) || json.optInt("max_completion_tokens") != 2_048 ||
            json.has("provider") || json.has("plugins") || json.has("tools")) return@runCatching null
        val messages = json.optJSONArray("messages") ?: return@runCatching null
        if (messages.length() !in 1..(WorkspaceLongInputPolicy.MAX_RECENT_MESSAGES + 1))
            return@runCatching null
        var characters = 0L
        for (i in 0 until messages.length()) {
            val entry = messages.optJSONObject(i) ?: return@runCatching null
            if (entry.optString("role") !in setOf("system", "user", "assistant")) return@runCatching null
            val content = entry.opt("content") as? String ?: return@runCatching null
            characters += content.length
            if (characters > WorkspaceGroqFree.MAX_PROMPT_CHARS) return@runCatching null
        }
        val last = messages.optJSONObject(messages.length() - 1) ?: return@runCatching null
        if (last.optString("role") != "user" ||
            last.getString("content").contains("\n\nDocument ")) return@runCatching null
        // Start with Groq's EXACT already-selected messages, including its generated system
        // instructions. No memory enrichment: that interceptor ran before this fallback.
        json.put("model", WorkspaceFreeAiSuggestion.MODEL)
            .remove("max_completion_tokens")
        json.put("max_tokens", WorkspaceFreeAiSuggestion.MAX_OUTPUT_TOKENS)
        json.put("provider", JSONObject()
            .put("zdr", true).put("data_collection", "deny")
            .put("allow_fallbacks", false)
            .put("max_price", JSONObject().put("prompt", 0).put("completion", 0)
                .put("request", 0).put("image", 0)))
        json.put("plugins", JSONArray().put(JSONObject().put("id", "context-compression")
            .put("enabled", false)))
        original.newBuilder().url(WorkspaceFreeAiSuggestion.ENDPOINT)
            .header("Authorization", "Bearer $openRouterKey")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }.getOrNull()
}
