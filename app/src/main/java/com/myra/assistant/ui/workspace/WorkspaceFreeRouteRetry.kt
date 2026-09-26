package com.myra.assistant.ui.workspace

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * One extra same-provider try only after a known HTTP rejection. If a Groq text request is
 * still rejected with 429/502/503/504, ONE approved OpenRouter Free attempt may follow.
 * Never retry unknown-outcome network errors, timeouts, payment/auth errors or partial output.
 * An alternate response is returned directly: no fallback loops or parallel requests.
 */
internal class WorkspaceFreeRouteRetry(
    private val alternate: (Request) -> Request? = WorkspaceFreeCrossProvider::fallbackRequest,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {
    companion object {
        const val MAX_RETRIES = 1
        private const val DEFAULT_WAIT_MS = 2_500L
        private const val MAX_WAIT_SECONDS = 8L

        /** A long/invalid Retry-After must not be overridden by a guessed short wait. */
        internal fun waitMillis(code: Int, retryAfter: String?): Long? = when (code) {
            429 -> {
                if (retryAfter.isNullOrBlank()) null
                else {
                    val seconds = retryAfter.takeIf { it.length <= 2 && it.all(Char::isDigit) }
                        ?.toLongOrNull()
                    if (seconds != null && seconds in 1..MAX_WAIT_SECONDS) seconds * 1_000L
                    else null
                }
            }
            502, 503, 504 -> if (retryAfter.isNullOrBlank()) DEFAULT_WAIT_MS else {
                val seconds = retryAfter.takeIf { it.length <= 2 && it.all(Char::isDigit) }
                    ?.toLongOrNull()
                if (seconds != null && seconds in 1..MAX_WAIT_SECONDS) seconds * 1_000L
                else null
            }
            else -> null
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var retries = 0
        while (true) {
            // OkHttp's original 35-second call timeout covers both attempts and fallback.
            if (chain.call().isCanceled()) throw IOException("Workspace request cancelled")
            val response = chain.proceed(request)
            WorkspaceProviderSessionHealth.recordResponse(response)
            val wait = if (retries < MAX_RETRIES)
                waitMillis(response.code, response.header("Retry-After")) else null
            if (wait == null || chain.call().isCanceled()) {
                // No response body from a rejected provider is read, copied or displayed.
                if (!chain.call().isCanceled() && WorkspaceFreeCrossProvider.eligibleStatus(response.code)) {
                    val fallback = runCatching { alternate(request) }.getOrNull()
                    if (fallback != null) {
                        response.close()
                        if (chain.call().isCanceled()) throw IOException("Workspace request cancelled")
                        return chain.proceed(fallback) // exactly one alternate; never retry it here
                    }
                }
                return response
            }
            response.close() // Close the rejected response before another send.
            retries++
            var remaining = wait
            while (remaining > 0) {
                if (chain.call().isCanceled()) throw IOException("Workspace request cancelled")
                val slice = minOf(remaining, 100L)
                try {
                    sleep(slice)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Workspace retry interrupted", interrupted)
                }
                remaining -= slice
            }
        }
    }
}
