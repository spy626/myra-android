package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

class WorkspaceFreeRouteDiagnosticsTest {
    private fun response(code: Int, retryAfter: String? = null): Response {
        val request = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").build()
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("private server text")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .body("SECRET API KEY AND CHAT TEXT".toResponseBody()).build()
    }

    @Test fun http429IsSeparateFromTimeoutAndCannotEchoProviderText() {
        val failure = runCatching { WorkspaceFreeAiSuggestion.readResponse(response(429, "17")) }
            .exceptionOrNull() ?: error("Expected an error")
        assertTrue(failure.message.orEmpty().contains("HTTP 429"))
        assertTrue(failure.message.orEmpty().contains("rate-limited"))
        assertTrue(failure.message.orEmpty().contains("17 seconds"))
        assertFalse(failure.message.orEmpty().contains("SECRET"))
        assertFalse(failure.message.orEmpty().contains("daily quota is exhausted."))
    }

    @Test fun http408IsAnUpstreamTimeoutNotRateLimit() {
        val failure = runCatching { WorkspaceFreeAiSuggestion.readResponse(response(408)) }
            .exceptionOrNull() ?: error("Expected an error")
        assertTrue(failure.message.orEmpty().contains("HTTP 408"))
        assertTrue(failure.message.orEmpty().contains("upstream request timed out"))
        assertFalse(failure.message.orEmpty().contains("rate-limited"))
        assertFalse(failure.message.orEmpty().contains("SECRET"))
    }

    @Test fun untrustedRetryAfterCannotLeakOrPretendToBeSafeWait() {
        val failure = WorkspaceFreeAiSuggestion.httpFailure(429, "my-secret-token")
        assertFalse(failure.contains("my-secret-token"))
        assertFalse(failure.contains("Server suggests"))
        assertTrue(WorkspaceFreeAiSuggestion.httpFailure(429, "999999999").contains("HTTP 429"))
    }

    @Test fun clientTimeoutIsNotReportedAsAnHttpStatus() {
        listOf(SocketTimeoutException("SECRET text"), InterruptedIOException("SECRET URL"))
            .forEach { error ->
                val message = WorkspaceFreeAiSuggestion.networkFailure(error)
                assertTrue(message.contains("Phone/network request timed out"))
                assertFalse(message.contains("HTTP 408"))
                assertFalse(message.contains("SECRET"))
            }
        val other = WorkspaceFreeAiSuggestion.networkFailure(IOException("private hostname"))
        assertTrue(other.contains("connection failed"))
        assertFalse(other.contains("private hostname"))
    }
}
