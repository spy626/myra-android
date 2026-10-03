package com.myra.assistant.ui.workspace

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WorkspaceFreeCrossProviderTest {
    private fun message(text: String) = WorkspaceConversationStore.Message("u1", "user", text, 1L)
    private fun groq(text: String = "Full user question / मत बदलो END") =
        WorkspaceGroqFree.request("groq-test-key", listOf(message(text)))

    @Test fun fallbackKeepsFullMessagesAndEnforcesOnlyZeroPriceZdrNoCompression() {
        val original = groq("START\n" + "हिंदी text ".repeat(300) + "\nEND")
        val alternate = WorkspaceFreeCrossProvider.openRouterRequest(original, "openrouter-test-key")
        assertNotNull(alternate)
        alternate!!
        assertEquals(WorkspaceFreeAiSuggestion.ENDPOINT, alternate.url.toString())
        assertEquals("Bearer openrouter-test-key", alternate.header("Authorization"))
        assertFalse(alternate.url.toString().contains("test-key"))
        val originalJson = JSONObject(okio.Buffer().also { original.body!!.writeTo(it) }.readUtf8())
        val alternateJson = JSONObject(okio.Buffer().also { alternate.body!!.writeTo(it) }.readUtf8())
        assertEquals(originalJson.getJSONArray("messages").toString(),
            alternateJson.getJSONArray("messages").toString())
        assertEquals(WorkspaceFreeAiSuggestion.MODEL, alternateJson.getString("model"))
        assertFalse(alternateJson.has("max_completion_tokens"))
        assertEquals(2_048, alternateJson.getInt("max_tokens"))
        val provider = alternateJson.getJSONObject("provider")
        assertTrue(provider.getBoolean("zdr"))
        assertEquals("deny", provider.getString("data_collection"))
        assertFalse(provider.getBoolean("allow_fallbacks"))
        val prices = provider.getJSONObject("max_price")
        for (kind in listOf("prompt", "completion", "request", "image"))
            assertEquals(0, prices.getInt(kind))
        assertFalse(alternateJson.getJSONArray("plugins").getJSONObject(0).getBoolean("enabled"))
        assertFalse(alternateJson.toString().contains("groq-test-key"))
    }

    @Test fun rejectIneligibleRequestBodiesAndPaymentOrAmbiguousStatuses() {
        assertTrue(WorkspaceFreeCrossProvider.eligibleStatus(429))
        assertTrue(WorkspaceFreeCrossProvider.eligibleStatus(502))
        assertTrue(WorkspaceFreeCrossProvider.eligibleStatus(503))
        assertTrue(WorkspaceFreeCrossProvider.eligibleStatus(504))
        for (code in listOf(200, 400, 401, 402, 403, 408, 413, 422, 500))
            assertFalse(WorkspaceFreeCrossProvider.eligibleStatus(code))
        assertNull(WorkspaceFreeCrossProvider.openRouterRequest(groq(), ""))
        assertNull(WorkspaceFreeCrossProvider.openRouterRequest(groq(), "key1,key2"))
        assertNull(WorkspaceFreeCrossProvider.openRouterRequest(groq(), "key with whitespace"))
        assertNull(WorkspaceFreeCrossProvider.openRouterRequest(
            groq().newBuilder().url("https://example.invalid/chat").build(), "key"))
        assertNull(WorkspaceFreeCrossProvider.openRouterRequest(groq("Before\n\nDocument a.txt:\nsecret"), "key"))
        val payload = JSONObject(okio.Buffer().also { groq().body!!.writeTo(it) }.readUtf8())
        payload.put("messages", org.json.JSONArray().put(JSONObject().put("role", "user")
            .put("content", org.json.JSONArray().put(JSONObject().put("type", "image_url")))))
        assertNull(WorkspaceFreeCrossProvider.openRouterRequest(groq().newBuilder()
            .post(payload.toString().toRequestBody()).build(), "key"))
        payload.put("messages", org.json.JSONArray().put(JSONObject().put("role", "user")
            .put("content", "x".repeat(WorkspaceGroqFree.MAX_PROMPT_CHARS + 1))))
        assertNull(WorkspaceFreeCrossProvider.openRouterRequest(groq().newBuilder()
            .post(payload.toString().toRequestBody()).build(), "key"))
    }

    private fun response(chain: Interceptor.Chain, status: Int, text: String = "done"): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(status).message("test status")
            .body(if (status == 200) "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"$text\"}}]}".toResponseBody()
                else "REJECTED SECRET BODY".toResponseBody())
            .build()

    @Test fun oneGroq429SwitchesOnceToOpenRouterAndUsesActualResponseParser() {
        var groqCalls = 0
        var openRouterCalls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(WorkspaceFreeRouteRetry(
                alternate = { WorkspaceFreeCrossProvider.openRouterRequest(it, "openrouter-test-key") },
                sleep = { }))
            .addInterceptor(Interceptor { chain ->
                if (chain.request().url.toString() == WorkspaceGroqFree.ENDPOINT) {
                    groqCalls++
                    response(chain, 429)
                } else {
                    openRouterCalls++
                    response(chain, 200, "Fallback reply")
                }
            }).build()
        val request = groq()
        client.newCall(request).execute().use { result ->
            assertEquals(200, result.code)
            assertEquals(WorkspaceFreeAiSuggestion.ENDPOINT, result.request.url.toString())
            assertEquals("Fallback reply", WorkspaceGroqFree.read(result))
        }
        assertEquals(1, groqCalls)
        assertEquals(1, openRouterCalls)
    }

    @Test fun oneSameProvider503RetryThenAtMostOneFallbackAndNoFallbackLoop() {
        var groqCalls = 0
        var openRouterCalls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(WorkspaceFreeRouteRetry(
                alternate = { WorkspaceFreeCrossProvider.openRouterRequest(it, "openrouter-test-key") },
                sleep = { }))
            .addInterceptor(Interceptor { chain ->
                if (chain.request().url.toString() == WorkspaceGroqFree.ENDPOINT) {
                    groqCalls++
                    response(chain, 503)
                } else {
                    openRouterCalls++
                    response(chain, 429)
                }
            }).build()
        client.newCall(groq()).execute().use { result ->
            assertEquals(429, result.code)
            assertEquals(WorkspaceFreeAiSuggestion.ENDPOINT, result.request.url.toString())
            val error = runCatching { WorkspaceGroqFree.read(result) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("OpenRouter returned HTTP 429"))
            assertFalse(error?.message.orEmpty().contains("REJECTED SECRET BODY"))
        }
        assertEquals(2, groqCalls)
        assertEquals(1, openRouterCalls)
    }

    @Test fun networkErrorAndCancelledCallNeverCrossProvider() {
        var fallbackChecks = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(WorkspaceFreeRouteRetry(alternate = { fallbackChecks++; groq() }, sleep = { }))
            .addInterceptor(Interceptor { throw IOException("unknown outcome") }).build()
        assertTrue(runCatching { client.newCall(groq()).execute() }.isFailure)
        assertEquals(0, fallbackChecks)
    }
}
