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

    @Test fun openRouterIsFreePrivacyConstrainedAndNeverSendsAnotherProject() {
        val request = WorkspaceChatGateway.request(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            "session-secret", listOf(message("user", "this project only")))
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(listOf(message("user", "this project only"))))
        assertEquals("openrouter/free", body.getString("model"))
        assertTrue(body.getJSONObject("provider").getBoolean("zdr"))
        assertEquals("deny", body.getJSONObject("provider").getString("data_collection"))
        assertFalse(body.getJSONObject("provider").getBoolean("allow_fallbacks"))
        assertEquals("this project only", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(request.url.toString().contains("session-secret"))
        assertFalse(body.toString().contains("session-secret"))
        assertEquals("Bearer session-secret", request.header("Authorization"))
    }

    @Test fun geminiUsesCodingEndpointWithHeaderAuthNotVoiceOrUrlKey() {
        val request = WorkspaceChatGateway.request(WorkspaceChatGateway.Provider.GEMINI_FREE_TIER,
            "session-secret", listOf(message("user", "build a website")))
        assertTrue(request.url.toString().contains(":generateContent"))
        assertFalse(request.url.toString().contains("session-secret"))
        assertEquals("session-secret", request.header("x-goog-api-key"))
        assertEquals(null, request.header("Authorization"))
        val body = JSONObject(WorkspaceChatGateway.geminiBody(listOf(message("user", "build a website"))))
        assertEquals("user", body.getJSONArray("contents").getJSONObject(0).getString("role"))
    }

    @Test fun photoSentOnlyInCurrentTurnAndNotRetainedInPreviousMessages() {
        val messages = listOf(message("user", "Earlier"), message("assistant", "Okay"), message("user", "Describe photo"))
        val image = WorkspaceChatGateway.Image("image/png", "cG5n")
        val openRouter = JSONObject(WorkspaceChatGateway.openRouterBody(messages, image))
            .getJSONArray("messages")
        assertEquals("Earlier", openRouter.getJSONObject(0).getString("content"))
        assertTrue(openRouter.getJSONObject(2).getJSONArray("content").getJSONObject(1)
            .getJSONObject("image_url").getString("url").startsWith("data:image/png;base64,"))
        val gemini = JSONObject(WorkspaceChatGateway.geminiBody(messages, image))
            .getJSONArray("contents")
        assertEquals(1, gemini.getJSONObject(0).getJSONArray("parts").length())
        assertEquals(2, gemini.getJSONObject(2).getJSONArray("parts").length())
    }

    @Test fun malformedAndTruncatedGeminiResponsesAreRefused() {
        assertTrue(runCatching { WorkspaceChatGateway.parseGemini("not json") }.isFailure)
        assertTrue(runCatching { WorkspaceChatGateway.parseGemini("""{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"partial"}]}}]}""") }.isFailure)
        val valid = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"hello"}]}}]}"""
        assertEquals("hello", WorkspaceChatGateway.parseGemini(valid))
    }

    @Test fun quotaFailureDoesNotExposeProviderBody() {
        val request = Request.Builder().url("https://generativelanguage.googleapis.com/").build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(429)
            .message("quota").body("secret echoed in response".toResponseBody()).build()
        val failure = runCatching {
            WorkspaceChatGateway.read(WorkspaceChatGateway.Provider.GEMINI_FREE_TIER, response)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("free-tier limit"))
        assertFalse(failure.message.orEmpty().contains("secret echoed"))
    }
}
