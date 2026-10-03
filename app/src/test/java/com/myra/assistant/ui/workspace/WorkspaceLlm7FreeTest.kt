package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

class WorkspaceLlm7FreeTest {
    private fun message(role: String, text: String) =
        WorkspaceConversationStore.Message("id", role, text, 1L)

    @Test fun requestUsesOnlyLlm7DefaultFreeRouteAndNoOpenrouterFields() {
        val original = "Hii bro short reply do"
        val body = JSONObject(WorkspaceLlm7Free.body(listOf(message("user", original))))
        assertEquals("default", body.getString("model"))
        assertFalse(body.has("provider"))
        assertFalse(body.has("plugins"))
        assertFalse(body.has("tools"))
        assertFalse(body.has("response_format"))
        assertEquals(2_048, body.getInt("max_tokens"))
        assertFalse(body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals(original, messages.getJSONObject(messages.length() - 1).getString("content"))

        val request = WorkspaceLlm7Free.request("free-token", listOf(message("user", original)))
        assertEquals("api.llm7.io", request.url.host)
        assertEquals("/v1/chat/completions", request.url.encodedPath)
        assertEquals("Bearer free-token", request.header("Authorization"))
        assertFalse(request.url.toString().contains("free-token"))
        assertFalse(body.toString().contains("free-token"))
    }

    @Test fun oneTurnInstructionsStayOnLlm7AndCountAgainstBudget() {
        val body = JSONObject(WorkspaceLlm7Free.body(
            listOf(message("user", "Task")),
            extraSystemInstructions = "SKILL-ONE-TURN"))
        assertEquals(WorkspaceLlm7Free.MODEL, body.getString("model"))
        assertFalse(body.has("provider"))
        assertTrue(body.getJSONArray("messages").getJSONObject(0)
            .getString("content").contains("SKILL-ONE-TURN"))
        assertFalse(WorkspaceLlm7Free.withinBudget(
            listOf(message("user", "Task")),
            "x".repeat(WorkspaceLlm7Free.MAX_PROMPT_CHARS)))
    }

    @Test fun routeIsTextOnlyAndConservativelyBounded() {
        assertTrue(WorkspaceLlm7Free.withinBudget(listOf(message("user", "hello"))))
        val tooLong = "x".repeat(WorkspaceLlm7Free.MAX_PROMPT_CHARS + 1)
        assertFalse(WorkspaceLlm7Free.withinBudget(listOf(message("user", tooLong))))
        assertTrue(runCatching {
            WorkspaceLlm7Free.body(listOf(message("user", "photo")),
                WorkspaceChatGateway.Image("image/png", "cG5n"))
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceLlm7Free.request("bad token", listOf(message("user", "hello")))
        }.isFailure)
    }

    @Test fun rejectedOrIncompleteReplyNeverLeaksProviderBody() {
        val request = Request.Builder().url(WorkspaceLlm7Free.ENDPOINT).build()
        val rejected = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(429).message("rate").body("PRIVATE PROVIDER BODY".toResponseBody()).build()
        val error = runCatching { WorkspaceLlm7Free.read(rejected) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("429"))
        assertTrue(error?.message.orEmpty().contains("No paid fallback"))
        assertFalse(error?.message.orEmpty().contains("PRIVATE PROVIDER BODY"))

        val partial = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK")
            .body("""{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}"""
                .toResponseBody()).build()
        assertTrue(runCatching { WorkspaceLlm7Free.read(partial) }.isFailure)
    }

    @Test fun transportUsesIntendedBoundedMobileTimeoutsAndAccurateMessages() {
        assertEquals(15_000, WorkspaceLlm7Free.client.connectTimeoutMillis)
        assertEquals(15_000, WorkspaceLlm7Free.client.writeTimeoutMillis)
        assertEquals(30_000, WorkspaceLlm7Free.client.readTimeoutMillis)
        assertEquals(35_000, WorkspaceLlm7Free.client.callTimeoutMillis)
        assertFalse(WorkspaceLlm7Free.client.retryOnConnectionFailure)
        assertFalse(WorkspaceLlm7Free.client.followRedirects)
        assertFalse(WorkspaceLlm7Free.client.followSslRedirects)

        val stage = WorkspaceLlm7Free.networkFailure(SocketTimeoutException("provider host"))
        assertTrue(stage.contains("connection/read timed out"))
        assertTrue(stage.contains("before"))
        assertFalse(stage.contains("timed out (LYRA limit: 35 seconds)"))
        assertFalse(stage.contains("provider host"))

        val overall = WorkspaceLlm7Free.networkFailure(InterruptedIOException("timeout"))
        assertTrue(overall.contains("35-second overall request limit"))
        assertFalse(overall.contains("timeout"))
    }

    @Test fun completeOpenaiCompatibleReplyIsAccepted() {
        val request = Request.Builder().url(WorkspaceLlm7Free.ENDPOINT).build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK")
            .body("""{"choices":[{"finish_reason":"stop","message":{"content":"Hii bro 👋"}}]}"""
                .toResponseBody()).build()
        assertEquals("Hii bro 👋", WorkspaceLlm7Free.read(response))
    }
}
