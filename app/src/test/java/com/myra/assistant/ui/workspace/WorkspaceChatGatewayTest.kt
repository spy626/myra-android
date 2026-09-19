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

class WorkspaceChatGatewayTest {
    private fun message(role: String, text: String) =
        WorkspaceConversationStore.Message("id", role, text, 1L)

    @Test fun onlyNonVoiceOpenRouterRouteIsExposed() {
        assertEquals(listOf(WorkspaceChatGateway.Provider.OPENROUTER_FREE),
            WorkspaceChatGateway.Provider.values().toList())
        val request = WorkspaceChatGateway.request(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            "session-secret", listOf(message("user", "this project only")))
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(listOf(message("user", "this project only"))))
        assertEquals("openrouter/free", body.getString("model"))
        assertTrue(body.getJSONObject("provider").getBoolean("zdr"))
        assertEquals("deny", body.getJSONObject("provider").getString("data_collection"))
        assertFalse(body.getJSONObject("provider").getBoolean("allow_fallbacks"))
        assertFalse(body.getJSONArray("plugins").getJSONObject(0).getBoolean("enabled"))
        assertEquals("this project only", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(request.url.toString().contains("session-secret"))
        assertFalse(body.toString().contains("session-secret"))
        assertEquals("Bearer session-secret", request.header("Authorization"))
        assertEquals(null, request.header("x-goog-api-key"))
        assertEquals("openrouter.ai", request.url.host)
    }

    @Test fun longPastedMessageIsSentInFullWithoutSlicing() {
        val original = "START\n" + "हॉरर कहानी और AI companion\n".repeat(850) + "\nEND"
        val older = message("assistant", "old".repeat(15000))
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(listOf(older, message("user", original))))
        val payload = body.getJSONArray("messages")
        assertEquals(original, payload.getJSONObject(payload.length() - 1).getString("content"))
        assertEquals(1, payload.length())
    }

    @Test fun photoSentOnlyInCurrentTurnAndNotRetainedInPreviousMessages() {
        val messages = listOf(message("user", "Earlier"), message("assistant", "Okay"), message("user", "Describe photo"))
        val image = WorkspaceChatGateway.Image("image/png", "cG5n")
        val openRouter = JSONObject(WorkspaceChatGateway.openRouterBody(messages, image))
            .getJSONArray("messages")
        assertEquals("Earlier", openRouter.getJSONObject(0).getString("content"))
        assertTrue(openRouter.getJSONObject(2).getJSONArray("content").getJSONObject(1)
            .getJSONObject("image_url").getString("url").startsWith("data:image/png;base64,"))
    }

    @Test fun quotaFailureDoesNotExposeProviderBodyOrRetry() {
        val request = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(429)
            .message("quota").body("secret echoed in response".toResponseBody()).build()
        val failure = runCatching {
            WorkspaceChatGateway.read(WorkspaceChatGateway.Provider.OPENROUTER_FREE, response)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("limit"))
        assertFalse(failure.message.orEmpty().contains("secret echoed"))
    }
}
