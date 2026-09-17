package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A single, user-authorized transport for an untrusted suggestion, NOT a second agent or file writer.
 * Only the published $0 OpenRouter free-model router is addressable here. No retry or paid fallback.
 */
internal object WorkspaceFreeAiSuggestion {
    const val MODEL = "openrouter/free"
    const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    private const val MAX_RESPONSE_BYTES = 32_768L
    private const val MAX_SUGGESTION_CHARS = 6_000

    val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun requestBody(prompt: String): String {
        require(prompt.isNotBlank() && prompt.length <= 12_000) { "Prompt is unavailable or exceeds local limit" }
        return JSONObject()
            .put("model", MODEL)
            .put("stream", false)
            .put("max_tokens", 750)
            .put("temperature", 0.2)
            .put("provider", JSONObject().put("zdr", true).put("data_collection", "deny"))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .toString()
    }

    /** The key is supplied for this one call only: never saved, included in the URL or logged. */
    fun request(key: String, prompt: String): Request {
        require(key.isNotBlank() && key.length <= 256 && key.none(Char::isWhitespace)) {
            "Enter a valid session-only OpenRouter API key"
        }
        val body = requestBody(prompt).toRequestBody("application/json; charset=utf-8".toMediaType())
        return Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
    }

    /** Bound response size; do not surface error bodies (they can echo submitted source or credentials). */
    fun readResponse(response: Response): String {
        response.use {
            require(it.isSuccessful) {
                when (it.code) {
                    401, 403 -> "Free AI key or provider permission refused. No paid fallback."
                    402 -> "Free AI route unavailable; payment will NOT be attempted."
                    408, 429 -> "Free AI limit or timeout reached. Try later; no paid fallback."
                    else -> "Free AI route refused (HTTP ${it.code}); no paid fallback."
                }
            }
            val peek = requireNotNull(it.peekBody(MAX_RESPONSE_BYTES + 1)) {
                "Free AI response was empty"
            }
            val bytes = peek.bytes()
            require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_RESPONSE_BYTES) {
                "Free AI response too large; no edit made"
            }
            return parseResponse(String(bytes, Charsets.UTF_8))
        }
    }

    /** Report a fixed, actionable category only. Never surface provider-supplied strings or partial code. */
    fun parseResponse(raw: String): String {
        require(raw.length in 1..32_768) { "Free AI response missing or too large; no edit made" }
        val root = runCatching { JSONObject(raw) }
            .getOrElse { throw IllegalArgumentException("Free AI returned an invalid response; no edit made") }
        require(!root.has("error")) { "Free AI provider returned an error; no edit made" }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        require(choice != null) { "Free AI returned no suggestion; no edit made" }
        when (choice.optString("finish_reason")) {
            "stop" -> Unit
            "length" -> throw IllegalArgumentException("Free AI reached its output-token limit; incomplete suggestion, no edit made")
            "content_filter" -> throw IllegalArgumentException("Free AI provider filtered the reply; no edit made")
            "tool_calls", "function_call" -> throw IllegalArgumentException("Free AI requested a tool instead of a patch; no edit made")
            else -> throw IllegalArgumentException("Free AI stopped without a complete suggestion; no edit made")
        }
        val content = choice.optJSONObject("message")?.opt("content")
        require(content is String && content.trim().length in 1..MAX_SUGGESTION_CHARS) {
            "Free AI returned no usable bounded text suggestion; no edit made"
        }
        return content.trim()
    }
}
