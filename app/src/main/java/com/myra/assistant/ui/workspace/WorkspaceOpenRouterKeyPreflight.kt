package com.myra.assistant.ui.workspace

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Checks OpenRouter credentials without submitting the user goal or project source.
 * Never expose a key, account information, or a provider response body in an error.
 */
internal object WorkspaceOpenRouterKeyPreflight {
    private const val ENDPOINT = "https://openrouter.ai/api/v1/key"
    private const val MAX_BYTES = 8_192L

    internal enum class Result { VERIFIED, ACCESS_REFUSED, UNVERIFIED }

    /** A rejected authentication status is distinct from an unavailable check. */
    internal fun classify(httpCode: Int, responseBody: String = ""): Result = when (httpCode) {
        401, 403 -> Result.ACCESS_REFUSED
        200 -> if (responseBody.length in 1..MAX_BYTES.toInt() && runCatching {
                JSONObject(responseBody).optJSONObject("data") != null
            }.getOrDefault(false)) Result.VERIFIED else Result.UNVERIFIED
        else -> Result.UNVERIFIED
    }

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Unavailable checks do not falsely label a key invalid or silently loosen routing. */
    internal fun verify(key: String): Result {
        if (!WorkspaceCodingAutoFallback.validKey(key)) return Result.UNVERIFIED
        val request = Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer $key").get().build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code != 200) return@use classify(response.code)
                val bytes = response.peekBody(MAX_BYTES + 1L).bytes()
                if (bytes.size > MAX_BYTES) Result.UNVERIFIED
                else classify(response.code, String(bytes, Charsets.UTF_8))
            }
        }.getOrDefault(Result.UNVERIFIED)
    }
}
