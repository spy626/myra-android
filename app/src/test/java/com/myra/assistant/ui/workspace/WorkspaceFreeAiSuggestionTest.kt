package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
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
        assertEquals(2048, WorkspaceFreeAiSuggestion.MAX_OUTPUT_TOKENS)
        assertEquals(WorkspaceFreeAiSuggestion.MAX_OUTPUT_TOKENS, json.getInt("max_tokens"))
        assertTrue(json.getJSONObject("provider").getBoolean("zdr"))
        assertEquals("deny", json.getJSONObject("provider").getString("data_collection"))
        val messages = json.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals(prompt, messages.getJSONObject(0).getString("content"))
        assertFalse(WorkspaceFreeAiSuggestion.client.retryOnConnectionFailure)
        assertFalse(WorkspaceFreeAiSuggestion.client.followRedirects)
        assertFalse(WorkspaceFreeAiSuggestion.client.followSslRedirects)
    }

    private fun reply(finishReason: String?, content: Any? = "sensitive source text should not appear"): String {
        val choice = JSONObject().put("message", JSONObject().put("content", content))
        if (finishReason != null) choice.put("finish_reason", finishReason)
        return JSONObject().put("choices", JSONArray().put(choice)).toString()
    }

    @Test fun validReplyIsOnlyUntrustedTextAndMalformedOrPartialRepliesFailClosed() {
        val patch = """{"schemaVersion":1,"operation":"replace_exact_once"}"""
        assertEquals(patch, WorkspaceFreeAiSuggestion.parseResponse(reply("stop", patch)))
        // Even a plausible, complete-looking patch MUST be refused when the provider reports truncation.
        assertTrue(runCatching { WorkspaceFreeAiSuggestion.parseResponse(reply("length", patch)) }.isFailure)
        assertTrue(runCatching { WorkspaceFreeAiSuggestion.parseResponse("not json") }.isFailure)
        assertTrue(runCatching { WorkspaceFreeAiSuggestion.parseResponse("x".repeat(33_000)) }.isFailure)
        assertTrue(runCatching { WorkspaceFreeAiSuggestion.parseResponse(reply("stop", "")) }.isFailure)
        assertTrue(runCatching { WorkspaceFreeAiSuggestion.parseResponse(reply("stop", JSONObject())) }.isFailure)
    }

    @Test fun responseReasonIsActionableButNeverEchoesProviderTextOrClaimsAWrite() {
        val cases = listOf(
            "length" to "output-token limit",
            "content_filter" to "filtered",
            "tool_calls" to "tool instead of a patch",
            "custom-provider-status-SECRET" to "without a complete suggestion",
        )
        for ((reason, expected) in cases) {
            val message = runCatching { WorkspaceFreeAiSuggestion.parseResponse(reply(reason)) }
                .exceptionOrNull()?.message.orEmpty()
            assertTrue("Reason $reason should have safe category: $message", message.contains(expected))
            assertTrue(message.contains("no edit made"))
            assertFalse(message.contains("sensitive source text"))
            assertFalse(message.contains("SECRET"))
        }
        assertTrue(runCatching { WorkspaceFreeAiSuggestion.parseResponse(reply(null)) }
            .exceptionOrNull()?.message.orEmpty().contains("without a complete suggestion"))
        assertTrue(runCatching { WorkspaceFreeAiSuggestion.parseResponse("{}") }
            .exceptionOrNull()?.message.orEmpty().contains("no suggestion"))
        val remoteError = JSONObject().put("error", JSONObject().put("message", "PRIVATE_SOURCE_SHOULD_NOT_APPEAR"))
            .toString()
        val error = runCatching { WorkspaceFreeAiSuggestion.parseResponse(remoteError) }
            .exceptionOrNull()?.message.orEmpty()
        assertTrue(error.contains("provider returned an error"))
        assertFalse(error.contains("PRIVATE_SOURCE_SHOULD_NOT_APPEAR"))
    }

    @Test fun httpErrorCannotLeakProviderBodyAndNoAutomaticRetryOrPaidFallback() {
        val request = WorkspaceFreeAiSuggestion.request("key", "harmless test")
        for ((status, fragment) in listOf(402 to "payment will NOT be attempted", 429 to "HTTP 429: free route rate-limited")) {
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("Provider message may be private")
                .body("PRIVATE_SOURCE_SHOULD_NOT_APPEAR".toResponseBody("text/plain".toMediaType()))
                .build()
            val error = runCatching { WorkspaceFreeAiSuggestion.readResponse(response) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty().contains(fragment))
            assertFalse(error.message.orEmpty().contains("PRIVATE_SOURCE_SHOULD_NOT_APPEAR"))
        }
    }

    @Test fun invalidKeyAndOversizedPromptNeverCreateRequest() {
        assertTrue(runCatching { WorkspaceFreeAiSuggestion.request("bad key", "hello") }.isFailure)
        assertTrue(runCatching { WorkspaceFreeAiSuggestion.request("key", "x".repeat(12_001)) }.isFailure)
    }
}
