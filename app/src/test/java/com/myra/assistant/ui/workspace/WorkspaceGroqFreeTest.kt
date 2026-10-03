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

    @Test fun groqPhoneOnlyPlanningWithRuntimeFitsWithoutDroppingFullTurn() {
        val original = "bro mere paas sirf Android phone hai aur mujhe free mein ek simple " +
            "grocery app banana hai. Sabse pehle kya karna chahiye? 3 practical steps batao, " +
            "abhi coding start mat karna 😂"
        val messages = listOf(message("user", original))
        val runtime = WorkspaceRuntimeSelfModel.instructions(
            WorkspaceRuntimeSelfModel.Snapshot()
        )
        val count = WorkspaceGroqFree.promptChars(messages, runtime)
        assertTrue("Compact Groq prompt must fit the same preflight/HTTP cap: $count",
            count != null && count <= WorkspaceGroqFree.MAX_PROMPT_CHARS)
        assertTrue(WorkspaceGroqFree.withinBudget(messages, runtime))
        val body = JSONObject(WorkspaceGroqFree.body(messages,
            extraSystemInstructions = runtime))
        val payload = body.getJSONArray("messages")
        val system = payload.getJSONObject(0).getString("content")
        assertTrue(system.contains("PRACTICAL PLANNING"))
        val regular = WorkspaceChatGateway.openAiMessages(
            messages, extraSystemInstructions = runtime
        )
        val regularSystem = regular.getJSONObject(0).getString("content")
        if (regularSystem.length + original.length > WorkspaceGroqFree.MAX_PROMPT_CHARS) {
            assertTrue(system.contains("PRACTICAL PLANNING (compact Groq Free"))
            assertTrue(system.contains("EXACT MAIN STEP COUNT: give exactly 3"))
            assertTrue(system.contains("PLANNING-ONLY HARD STOP"))
            assertTrue(system.contains("NO coding, signup, builder launch"))
        } else {
            assertEquals(regularSystem, system)
        }
        assertEquals(original,
            payload.getJSONObject(payload.length() - 1).getString("content"))
        assertEquals(WorkspaceChatGateway.Provider.GROQ_FREE,
            WorkspaceFreeProviderSelection.choose(
                openRouterAvailable = false,
                groqAvailable = true,
                groqFreeZdrApproved = true,
                groqWithinBudget = WorkspaceGroqFree.withinBudget(messages, runtime),
                hasAttachments = false,
            ))
        assertFalse(body.has("provider"))
        assertFalse(body.has("plugins"))
    }

    @Test fun groqTextRequestUsesOnlyGroqEndpointAndModel() {
        val original = "LYRA ke liye AI companion prompt do"
        val payload = JSONObject(WorkspaceGroqFree.body(listOf(message("user", original))))
        assertEquals(WorkspaceGroqFree.MODEL, payload.getString("model"))
        assertEquals(original, payload.getJSONArray("messages").getJSONObject(payload.getJSONArray("messages").length() - 1).getString("content"))
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

    @Test fun oneTurnInstructionsStayOnGroqAndCountAgainstBudget() {
        val body = JSONObject(WorkspaceGroqFree.body(
            listOf(message("user", "Task")),
            extraSystemInstructions = "SKILL-ONE-TURN"))
        assertEquals(WorkspaceGroqFree.MODEL, body.getString("model"))
        assertFalse(body.has("provider"))
        assertTrue(body.getJSONArray("messages").getJSONObject(0)
            .getString("content").contains("SKILL-ONE-TURN"))
        assertFalse(WorkspaceGroqFree.withinBudget(
            listOf(message("user", "Task")),
            "x".repeat(WorkspaceGroqFree.MAX_PROMPT_CHARS)))
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

    @Test fun entireProviderPromptBudgetIncludesSystemAndHistory() {
        val near = message("user", "AI companion banane ke liye promt do " + "x".repeat(11_950))
        assertFalse(WorkspaceGroqFree.withinBudget(listOf(near)))
        assertTrue(runCatching { WorkspaceGroqFree.body(listOf(near)) }.isFailure)
        val history = listOf(message("user", "first"),
            message("assistant", "a".repeat(11_990)), message("user", "follow up"))
        // The latest user turn survives; ONLY the outbound copy of oversized
        // older conversation turns is pruned before a Groq Free request.
        assertTrue(WorkspaceGroqFree.withinBudget(history))
        val projected = JSONObject(WorkspaceGroqFree.body(history)).getJSONArray("messages")
        assertEquals("follow up", projected.getJSONObject(projected.length() - 1)
            .getString("content"))
        assertEquals(2, projected.length())
        assertEquals(WorkspaceChatGateway.Provider.GROQ_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true,
                WorkspaceGroqFree.withinBudget(history), false))
    }


    @Test fun longRealChatIsCompactedWithoutChangingLatestOrMandatoryRuntimeContext() {
        val latest = "bro mere paas sirf Android phone hai. 3 practical steps batao, " +
            "abhi coding start mat karna"
        val history = mutableListOf<WorkspaceConversationStore.Message>()
        repeat(8) { n ->
            history.add(message("user", "Older question $n " + "u".repeat(850)))
            history.add(message("assistant", "Older answer $n " + "a".repeat(950)))
        }
        history.add(message("user", latest))
        val runtime = "RUNTIME-REQUIRED-CURRENT-STATE"
        assertTrue(WorkspaceGroqFree.withinBudget(history, runtime))
        val request = JSONObject(WorkspaceGroqFree.body(history,
            extraSystemInstructions = runtime)).getJSONArray("messages")
        val preflightChars = WorkspaceGroqFree.promptChars(history, runtime)
        assertTrue(preflightChars != null && preflightChars <= WorkspaceGroqFree.MAX_PROMPT_CHARS)
        assertEquals(latest, request.getJSONObject(request.length() - 1)
            .getString("content"))
        assertTrue(request.getJSONObject(0).getString("content").contains(runtime))
        assertTrue(request.getJSONObject(0).getString("content")
            .contains("PLANNING-ONLY HARD STOP"))
        assertTrue(request.length() < WorkspaceLongInputPolicy.outbound(history).size + 1)
        assertFalse(JSONObject(WorkspaceGroqFree.body(history,
            extraSystemInstructions = runtime)).has("provider"))
    }

    @Test fun noHistoryTrimmingCanSendAnOversizedLatestOrRequiredRuntimeInstruction() {
        val single = listOf(message("user", "X".repeat(
            WorkspaceGroqFree.MAX_PROMPT_CHARS + 1)))
        assertFalse(WorkspaceGroqFree.withinBudget(single))
        assertTrue(runCatching { WorkspaceGroqFree.body(single) }.isFailure)
        val extras = "AUTHORITATIVE-EXTRA-" + "Z".repeat(
            WorkspaceGroqFree.MAX_PROMPT_CHARS)
        val latest = listOf(message("user", "hello"))
        assertFalse(WorkspaceGroqFree.withinBudget(latest, extras))
        assertTrue(runCatching {
            WorkspaceGroqFree.body(latest, extraSystemInstructions = extras)
        }.isFailure)
    }

    @Test fun attachmentsNeverChooseGroqAndDoNotAutoEnableWithoutConsent() {
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, true))
        assertEquals(null, WorkspaceFreeProviderSelection.choose(false, true, true, true, true))
        assertEquals(null, WorkspaceFreeProviderSelection.choose(false, true, false, true, false))
        assertEquals(WorkspaceChatGateway.Provider.GROQ_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, false))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, false, true, false))
    }
}
