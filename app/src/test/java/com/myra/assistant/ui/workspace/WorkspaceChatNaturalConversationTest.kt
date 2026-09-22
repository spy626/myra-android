package com.myra.assistant.ui.workspace

import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceChatNaturalConversationTest {
    private fun turn(role: String, text: String) =
        WorkspaceConversationStore.Message("$role-${text.length}", role, text, 1L)

    private fun content(request: Request): JSONObject {
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    private fun instructions(messages: JSONArray): String {
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        return messages.getJSONObject(0).getString("content")
    }

    @Test fun casualConversationUsesSameGenericDisciplineAcrossAllThreeFreeRoutes() {
        val conversation = listOf(
            turn("user", "I'm joining a chess club tomorrow. Talk to me like a friend, short replies please."),
            turn("assistant", "You are going hiking with friends."),
            turn("user", "Sounds good."))
        val openRouter = JSONObject(WorkspaceChatGateway.openRouterBody(conversation))
            .getJSONArray("messages")
        val groq = JSONObject(WorkspaceGroqFree.body(conversation)).getJSONArray("messages")
        val cloudflare = content(WorkspaceCloudflareFree.chatRequest(
            "test-token-not-real", "0123456789abcdef0123456789abcdef", conversation))
            .getJSONArray("messages")
        val guidance = instructions(openRouter)
        assertEquals(guidance, instructions(groq))
        assertEquals(guidance, instructions(cloudflare))
        assertTrue(guidance.contains("briefly acknowledges a previous reply"))
        assertTrue(guidance.contains("plan or personal update is not a request for instructions"))
        assertTrue(guidance.contains("end the conversation only when the user actually signals"))
        assertTrue(guidance.contains("only when useful, one relevant follow-up question"))
        assertTrue(guidance.contains("not small talk"))
        assertTrue(guidance.contains("Earlier assistant replies can be mistaken"))
        // The guidance is not a prompt-specific script or invented user history.
        assertFalse(guidance.contains("chess club"))
        assertFalse(guidance.contains("hiking"))
        listOf(openRouter, groq, cloudflare).forEach { entries ->
            assertEquals(4, entries.length())
            assertEquals(conversation[0].text, entries.getJSONObject(1).getString("content"))
            assertEquals(conversation[1].text, entries.getJSONObject(2).getString("content"))
            assertEquals(conversation[2].text, entries.getJSONObject(3).getString("content"))
        }
        assertEquals("openrouter/free", openRouter.let {
            JSONObject(WorkspaceChatGateway.openRouterBody(conversation)).getString("model") })
        assertEquals(WorkspaceGroqFree.MODEL,
            JSONObject(WorkspaceGroqFree.body(conversation)).getString("model"))
        assertTrue(WorkspaceCloudflareFree.chatRequest("test-token-not-real",
            "0123456789abcdef0123456789abcdef", conversation).url.toString()
            .endsWith("/ai/run/${WorkspaceCloudflareFree.MODEL}"))
    }

    @Test fun taskAndCreativeInstructionsStillTakePriorityWithoutRewritingUserText() {
        val story = "Write a mystery story with a surprising ending."
        val storyEntries = JSONObject(WorkspaceChatGateway.openRouterBody(listOf(turn("user", story))))
            .getJSONArray("messages")
        assertTrue(instructions(storyEntries).contains("STORY: Start the complete STORY"))
        assertEquals(story, storyEntries.getJSONObject(1).getString("content"))
        val technical = "Explain how a binary search works, with an example and edge cases."
        val taskEntries = JSONObject(WorkspaceChatGateway.openRouterBody(listOf(turn("user", technical))))
            .getJSONArray("messages")
        assertTrue(instructions(taskEntries).contains("fulfill the actual request with its needed detail and format"))
        assertEquals(technical, taskEntries.getJSONObject(1).getString("content"))
    }
}
