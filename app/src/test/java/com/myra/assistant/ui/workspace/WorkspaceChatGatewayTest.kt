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

    private fun assertZeroPrice(body: JSONObject) {
        val ceiling = body.getJSONObject("provider").getJSONObject("max_price")
        listOf("prompt", "completion", "request", "image").forEach { category ->
            assertEquals("Nonzero provider price for $category", 0, ceiling.getInt(category))
        }
    }

    @Test fun onlyExplicitWorkspaceFreeRoutesAreExposed() {
        assertEquals(listOf(
            WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceChatGateway.Provider.GROQ_FREE,
            WorkspaceChatGateway.Provider.LLM7_FREE),
            WorkspaceChatGateway.Provider.values().toList())
        val request = WorkspaceChatGateway.request(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            "session-secret", listOf(message("user", "this project only")))
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(listOf(message("user", "this project only"))))
        assertEquals("openrouter/free", body.getString("model"))
        assertTrue(body.getJSONObject("provider").getBoolean("zdr"))
        assertEquals("deny", body.getJSONObject("provider").getString("data_collection"))
        assertFalse(body.getJSONObject("provider").getBoolean("allow_fallbacks"))
        assertZeroPrice(body)
        assertFalse(body.getJSONArray("plugins").getJSONObject(0).getBoolean("enabled"))
        val initial = body.getJSONArray("messages")
        assertEquals("system", initial.getJSONObject(0).getString("role"))
        assertEquals("this project only", initial.getJSONObject(initial.length() - 1).getString("content"))
        assertFalse(request.url.toString().contains("session-secret"))
        assertFalse(body.toString().contains("session-secret"))
        assertEquals("Bearer session-secret", request.header("Authorization"))
        assertEquals(null, request.header("x-goog-api-key"))
        assertEquals("openrouter.ai", request.url.host)
    }

    @Test fun oneTurnSystemInstructionsAreOptionalBoundedAndDoNotRewriteUserTurn() {
        val messages = listOf(message("user", "Review this response"))
        val baseline = WorkspaceChatGateway.openRouterBody(messages)
        assertEquals(baseline, WorkspaceChatGateway.openRouterBody(
            messages, extraSystemInstructions = null))

        val extra = "LYRA ENABLED SKILL — TEST ONLY"
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(
            messages, extraSystemInstructions = extra))
        val projected = body.getJSONArray("messages")
        val system = projected.getJSONObject(0).getString("content")
        assertTrue(system.contains(extra))
        assertEquals(1, Regex(Regex.escape(extra)).findAll(system).count())
        assertEquals("Review this response",
            projected.getJSONObject(projected.length() - 1).getString("content"))

        assertTrue(runCatching {
            WorkspaceChatGateway.openRouterBody(
                messages, extraSystemInstructions = "x".repeat(24_001))
        }.isFailure)
    }

    @Test fun longPastedMessageIsSentInFullWithoutSlicing() {
        val original = "START\n" + "हॉरर कहानी और AI companion\n".repeat(850) + "\nEND"
        val olderThatFits = message("assistant", "old".repeat(15000))
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(listOf(olderThatFits, message("user", original))))
        assertZeroPrice(body)
        val payload = body.getJSONArray("messages")
        assertEquals(3, payload.length())
        assertEquals(original, payload.getJSONObject(payload.length() - 1).getString("content"))

        val olderTooLarge = message("assistant", "x".repeat(80_000))
        val bounded = JSONObject(WorkspaceChatGateway.openRouterBody(listOf(olderTooLarge, message("user", original))))
            .getJSONArray("messages")
        assertEquals(2, bounded.length())
        assertEquals(original, bounded.getJSONObject(bounded.length() - 1).getString("content"))
    }

    @Test fun photoSentOnlyInCurrentTurnAndNotRetainedInPreviousMessages() {
        val messages = listOf(message("user", "Earlier"), message("assistant", "Okay"), message("user", "Describe photo"))
        val image = WorkspaceChatGateway.Image("image/png", "cG5n")
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(messages, image))
        assertZeroPrice(body)
        val openRouter = body.getJSONArray("messages")
        assertEquals("Earlier", openRouter.getJSONObject(1).getString("content"))
        assertTrue(openRouter.getJSONObject(3).getJSONArray("content").getJSONObject(1)
            .getJSONObject("image_url").getString("url").startsWith("data:image/png;base64,"))
    }

    @Test fun practicalPlanningInstructionsReachAllFreeProvidersWithoutChangingLatestTurn() {
        val original = "bro mere paas sirf Android phone hai aur mujhe free mein ek simple " +
            "grocery app banana hai. Sabse pehle kya karna chahiye? 3 practical steps " +
            "batao, abhi coding start mat karna 😂"
        val messages = listOf(message("user", original))
        val common = WorkspaceChatGateway.openAiMessages(messages)
        val system = common.getJSONObject(0).getString("content")
        assertTrue(system.contains("PRACTICAL PLANNING RESPONSE GUIDANCE"))
        assertTrue(system.contains("ONE coherent feasible starting route"))
        assertTrue(system.contains("exactly that many MAIN steps"))
        assertTrue(system.contains("do NOT claim to have coded"))
        assertTrue(system.contains("device the user HAS"))
        assertTrue(system.contains("minimum viable FIRST version"))
        assertTrue(system.contains("Defer IDE installation"))
        assertTrue(system.lastIndexOf("PRACTICAL PLANNING RESPONSE GUIDANCE") >
            system.lastIndexOf("Code-answer formatting when supplying code"))
        assertEquals(original, common.getJSONObject(common.length() - 1).getString("content"))

        val groq = JSONObject(WorkspaceGroqFree.body(messages)).getJSONArray("messages")
        val llm7 = JSONObject(WorkspaceLlm7Free.body(messages)).getJSONArray("messages")
        listOf(groq, llm7).forEach { out ->
            assertTrue(out.getJSONObject(0).getString("content")
                .contains("PRACTICAL PLANNING RESPONSE GUIDANCE"))
            assertEquals(original,
                out.getJSONObject(out.length() - 1).getString("content"))
        }
        assertFalse(WorkspaceChatGateway.openAiMessages(listOf(
            message("user", "hi bro, how are you today?")
        )).getJSONObject(0).getString("content")
            .contains("PRACTICAL PLANNING RESPONSE GUIDANCE"))
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
    @Test fun normalChatGetsSemanticContinuityWithoutRewritingLatestUserTurn() {
        val messages = listOf(
            message("user", "Earlier we compared repository handoff architecture."),
            message("assistant", "Okay."),
            message("user", "bro wahi concept se continue karo"),
        )
        val payload = WorkspaceChatGateway.openAiMessages(messages)
        val system = payload.getJSONObject(0).getString("content")

        assertTrue(system.contains("SEMANTIC TASK CONTINUITY"))
        assertTrue(system.contains("repository handoff architecture"))
        assertTrue(system.contains("current-turn execution gates remain separate"))
        assertEquals(
            "bro wahi concept se continue karo",
            payload.getJSONObject(payload.length() - 1).getString("content"),
        )
    }

}
