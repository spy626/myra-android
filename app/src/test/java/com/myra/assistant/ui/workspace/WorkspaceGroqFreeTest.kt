package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGroqFreeTest {
    private fun message(role: String, text: String) =
        WorkspaceConversationStore.Message("id", role, text, 1L)

    @Test fun groqTextRequestUsesOnlyGroqEndpointAndModel() {
        val original = "LYRA ke liye AI companion prompt do"
        val payload = JSONObject(WorkspaceGroqFree.body(listOf(message("user", original))))
        assertEquals(WorkspaceGroqFree.MODEL, payload.getString("model"))
        assertEquals(original, payload.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(payload.has("provider"))
        assertFalse(payload.has("plugins"))
        assertFalse(payload.has("tools"))
        assertEquals(2_048, payload.getInt("max_completion_tokens"))
        val request = WorkspaceGroqFree.request("groq-secret", listOf(message("user", original)))
        assertEquals("api.groq.com", request.url.host)
        assertFalse(request.url.toString().contains("groq-secret"))
        assertFalse(payload.toString().contains("groq-secret"))
        assertEquals("Bearer groq-secret", request.header("Authorization"))
        assertEquals(null, request.header("x-goog-api-key"))
    }

    @Test fun fullLatestMessagePreservedOrRequestRejectedWithoutTruncating() {
        val latest = "START\n" + "Hinglish kahani.\n".repeat(300) + "END"
        val outbound = JSONObject(WorkspaceGroqFree.body(listOf(message("user", latest))))
            .getJSONArray("messages")
        assertEquals(latest, outbound.getJSONObject(outbound.length() - 1).getString("content"))
        val tooLong = "Z".repeat(WorkspaceGroqFree.MAX_PROMPT_CHARS + 1)
        val error = runCatching { WorkspaceGroqFree.body(listOf(message("user", tooLong))) }
            .exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("saved locally"))
        assertTrue(runCatching { WorkspaceGroqFree.body(listOf(message("user", "photo")),
            WorkspaceChatGateway.Image("image/png", "cG5n")) }.isFailure)
    }

    @Test fun unfinishedOrRejectedGroqReplyNeverBecomesAssistantMessage() {
        val request = Request.Builder().url(WorkspaceGroqFree.ENDPOINT).build()
        val unfinished = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK")
            .body("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}".toResponseBody())
            .build()
        assertTrue(runCatching { WorkspaceGroqFree.read(unfinished) }.isFailure)
        val rejected = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(429).message("rate limited").body("SECRET PROVIDER BODY".toResponseBody()).build()
        val result = runCatching { WorkspaceGroqFree.read(rejected) }.exceptionOrNull()
        assertTrue(result?.message.orEmpty().contains("429"))
        assertFalse(result?.message.orEmpty().contains("SECRET PROVIDER BODY"))
    }
}
