package com.myra.assistant.ui.workspace

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WorkspaceFreeRouteRetryTest {
    @Test fun onlyShortServerDirected429AndTransientServerErrorsCanRetry() {
        assertEquals(null, WorkspaceFreeRouteRetry.waitMillis(429, null))
        assertEquals(null, WorkspaceFreeRouteRetry.waitMillis(429, "60"))
        assertEquals(null, WorkspaceFreeRouteRetry.waitMillis(429, "private-token"))
        assertEquals(2_000L, WorkspaceFreeRouteRetry.waitMillis(429, "2"))
        assertEquals(2_500L, WorkspaceFreeRouteRetry.waitMillis(503, null))
        assertEquals(2_500L, WorkspaceFreeRouteRetry.waitMillis(502, null))
        assertEquals(null, WorkspaceFreeRouteRetry.waitMillis(503, "120"))
        for (code in listOf(200, 400, 401, 402, 403, 408, 422, 500)) {
            assertEquals(null, WorkspaceFreeRouteRetry.waitMillis(code, null))
        }
    }

    private fun response(chain: Interceptor.Chain, status: Int, retryAfter: String? = null): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(status).message("test status")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .body("{}".toResponseBody()).build()

    private fun request() = Request.Builder().url("https://example.invalid/test")
        .post("{}".toRequestBody()).build()

    @Test fun oneRetryAtMostAndSameRequestForAcceptedServerError() {
        var calls = 0
        val waits = mutableListOf<Long>()
        val client = OkHttpClient.Builder()
            .addInterceptor(WorkspaceFreeRouteRetry { waits.add(it) })
            .addInterceptor(Interceptor { chain ->
                calls++
                response(chain, if (calls == 1) 503 else 200)
            }).build()
        client.newCall(request()).execute().use { assertEquals(200, it.code) }
        assertEquals(2, calls)
        assertEquals(2_500L, waits.sum())
        calls = 0
        client.newCall(request()).execute().use { assertEquals(200, it.code) }
        assertEquals(2, calls)
    }

    @Test fun continued429StopsAfterOneRetryAndMissingWaitNeverRetries() {
        var calls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(WorkspaceFreeRouteRetry { })
            .addInterceptor(Interceptor { chain ->
                calls++
                response(chain, 429, "2")
            }).build()
        client.newCall(request()).execute().use { assertEquals(429, it.code) }
        assertEquals(2, calls)
        calls = 0
        val withoutHeader = OkHttpClient.Builder()
            .addInterceptor(WorkspaceFreeRouteRetry { })
            .addInterceptor(Interceptor { chain -> calls++; response(chain, 429) }).build()
        withoutHeader.newCall(request()).execute().use { assertEquals(429, it.code) }
        assertEquals(1, calls)
    }

    @Test fun timeoutAndConnectionFailureNeverRetry() {
        var calls = 0
        val timeout = OkHttpClient.Builder()
            .addInterceptor(WorkspaceFreeRouteRetry { })
            .addInterceptor(Interceptor { chain -> calls++; response(chain, 408) }).build()
        timeout.newCall(request()).execute().use { assertEquals(408, it.code) }
        assertEquals(1, calls)
        calls = 0
        val failed = OkHttpClient.Builder()
            .addInterceptor(WorkspaceFreeRouteRetry { })
            .addInterceptor(Interceptor { calls++; throw IOException("network unavailable") }).build()
        assertTrue(runCatching { failed.newCall(request()).execute() }.isFailure)
        assertEquals(1, calls)
    }

    @Test fun stopDuringBackoffPreventsSecondSend() {
        var calls = 0
        lateinit var activeCall: okhttp3.Call
        val client = OkHttpClient.Builder()
            .addInterceptor(WorkspaceFreeRouteRetry { activeCall.cancel() })
            .addInterceptor(Interceptor { chain -> calls++; response(chain, 503) }).build()
        activeCall = client.newCall(request())
        assertTrue(runCatching { activeCall.execute() }.isFailure)
        assertTrue(activeCall.isCanceled())
        assertEquals(1, calls)
    }
}
