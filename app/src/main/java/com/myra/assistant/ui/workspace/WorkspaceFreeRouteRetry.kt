package com.myra.assistant.ui.workspace

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * One extra attempt for an explicitly sent Workspace request after a known HTTP rejection.
 * No retry on unknown-outcome connection failures, client timeouts, HTTP 408, 4xx other than
 * a short server-directed 429, or any successful response. The same request and free route
 * are used; this layer never changes provider, model, project state, or files.
 */
internal class WorkspaceFreeRouteRetry(
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
            // OkHttp's original 35-second call timeout also covers backoff and the next try.
            if (chain.call().isCanceled()) throw IOException("Workspace request cancelled")
            val response = chain.proceed(request)
            val wait = if (retries < MAX_RETRIES) waitMillis(response.code, response.header("Retry-After"))
                else null
            if (wait == null || chain.call().isCanceled()) return response
            // Close the rejected response before reissuing the identical request.
            response.close()
            retries++
            // Short slices make the existing Stop button effective during the wait.
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
