package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** A bounded free request; no automatic retry or paid fallback and no raw provider error bodies. */
internal object WorkspaceFreeAiSuggestion {
    const val MODEL = "openrouter/free"
    const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    const val MAX_OUTPUT_TOKENS = 2_048
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
            .put("max_tokens", MAX_OUTPUT_TOKENS)
            .put("temperature", 0.2)
            .put("provider", JSONObject().put("zdr", true).put("data_collection", "deny"))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .toString()
    }

    /** Key is only in the Authorization header; never copy it into diagnostics. */
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

    /** Accept only a small integer delay from Retry-After, never arbitrary provider header text. */
    private fun retryAfterHint(header: String?): String {
        if (header == null || header.length !in 1..6 || !header.all { it in '0'..'9' }) return ""
        val seconds = header.toLongOrNull() ?: return ""
        if (seconds !in 1..86_400) return ""
        return " Server suggests waiting $seconds seconds."
    }

    /** An upstream HTTP status is not a phone/network timeout or proof of daily quota exhaustion. */
    internal fun httpFailure(code: Int, retryAfter: String? = null): String = when (code) {
        401, 403 -> "OpenRouter returned HTTP $code: key or provider access refused. No paid fallback."
        402 -> "OpenRouter returned HTTP 402: free route unavailable; payment will NOT be attempted."
        408 -> "OpenRouter returned HTTP 408: upstream request timed out. Try again later; no paid fallback."
        429 -> "OpenRouter returned HTTP 429: free route rate-limited. This does not prove your daily quota is exhausted.${retryAfterHint(retryAfter)} Wait as suggested, or try later instead of repeatedly tapping Retry. No paid fallback."
        503 -> "OpenRouter returned HTTP 503: service temporarily unavailable. Try later; no paid fallback."
        else -> "OpenRouter returned HTTP $code: free route refused; no paid fallback."
    }

    /** Never include exception.message: it could include a URL, provider body or sensitive text. */
    internal fun networkFailure(error: IOException): String = when (error) {
        is SocketTimeoutException, is InterruptedIOException ->
            "Phone/network request timed out (LYRA limit: 35 seconds). No HTTP response was received; no paid fallback."
        else -> "Phone/network connection failed before a usable response. No HTTP status confirmed; no paid fallback."
    }

    /** Bound response size; never display, log or parse a failed response body. */
    fun readResponse(response: Response): String {
        response.use {
            require(it.isSuccessful) { httpFailure(it.code, it.header("Retry-After")) }
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

    /** Report only fixed categories; never expose provider text or accept a partial patch. */
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
