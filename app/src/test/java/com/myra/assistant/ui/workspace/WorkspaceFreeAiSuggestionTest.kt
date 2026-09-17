package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceFreeAiSuggestionTest {
    @Test fun requestIsPinnedToFreeModelWithStrictPrivacyAndNoKeyInBodyOrUrl() {
        val key = "sk-or-v1-test-only-do-not-use"
        val prompt = "Goal: update a harmless heading"
        val request = WorkspaceFreeAiSuggestion.request(key, prompt)
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        val raw = buffer.readUtf8()
        val json = JSONObject(raw)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer $key", request.header("Authorization"))
        assertFalse(raw.contains(key))
        assertFalse(request.url.toString().contains(key))
        assertEquals("openrouter/free", json.getString("model"))
        assertFalse(json.getBoolean("stream"))
        assertTrue(json.getJSONObject("provider").getBoolean("zdr"))
        assertEquals("deny", json.getJSONObject("provider").getString("data_collection"))
        val messages = json.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals(prompt, messages.getJSONObject(0).getString("content"))
        assertFalse(WorkspaceFreeAiSuggestion.client.retryOnConnectionFailure)
        assertFalse(WorkspaceFreeAiSuggestion.client.followRedirects)
    }

    @Test fun validReplyIsOnlyUntrustedTextAndMalformedOrPartialRepliesFailClosed() {
        val patch = """{"schemaVersion":1,"operation":"replace_exact_once"}"""
        val response = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("finish_reason", "stop")
            .put("message", JSONObject().put("content", patch)))).toString()
        assertEquals(patch, WorkspaceFreeAiSuggestion.parseResponse(response))
        assertTrue(runCatching { WorkspaceFreeAiSuggestion.parseResponse("not json") }.isFailure)
        assertTrue(runCatching { WorkspaceFreeAiSuggestion.parseResponse("{}") }.isFailure)
        assertTrue(runCatching { WorkspaceFreeAiSuggestion.parseResponse(response.replace("\"stop\"", "\"length\"")) }.isFailure)
        assertTrue(runCatching { WorkspaceFreeAiSuggestion.parseResponse("x".repeat(33_000)) }.isFailure)
    }

    @Test fun httpErrorCannotLeakProviderBodyAndNoAutomaticRetryOrPaidFallback() {
        val request = WorkspaceFreeAiSuggestion.request("key", "harmless test")
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(402).message("Payment required")
            .body("PRIVATE_SOURCE_SHOULD_NOT_APPEAR".toResponseBody("text/plain".toMediaType()))
            .build()
        val error = runCatching { WorkspaceFreeAiSuggestion.readResponse(response) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("payment will NOT be attempted"))
        assertFalse(error.message.orEmpty().contains("PRIVATE_SOURCE_SHOULD_NOT_APPEAR"))
    }

    @Test fun invalidKeyAndOversizedPromptNeverCreateRequest() {
        assertTrue(runCatching { WorkspaceFreeAiSuggestion.request("bad key", "hello") }.isFailure)
        assertTrue(runCatching { WorkspaceFreeAiSuggestion.request("key", "x".repeat(12_001)) }.isFailure)
    }
}
