package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceChatTurnFrameTest {
    private fun turn(role: String, text: String, index: Int) =
        WorkspaceConversationStore.Message("turn-$index", role, text, index.toLong())

    @Test fun activeUserTopicAndClarificationSurviveAnInventedAssistantPlace() {
        val messages = listOf(
            turn("user", "I am joining a chess club tomorrow. Talk like a friend, short reply please.", 1),
            turn("assistant", "You said you're going to Paris for a football match.", 2),
            turn("user", "What do you mean?", 3))
        val context = WorkspaceChatTurnFrame.instructions(messages)
        assertTrue(context.contains("chess club tomorrow"))
        assertTrue(context.contains("What do you mean?"))
        assertTrue(context.contains("asking for clarification"))
        assertFalse(context.contains("Paris"))
        assertFalse(context.contains("football match"))
        assertEquals("What do you mean?", messages.last().text)
    }

    @Test fun acknowledgementsAreNotGoodbyesAndTechTasksStayIndependent() {
        val prior = listOf(
            turn("user", "My sister has an important exam tomorrow. Short replies, please.", 1),
            turn("assistant", "That sounds important!", 2))
        assertTrue(WorkspaceChatTurnFrame.instructions(prior + turn("user", "Okay.", 3))
            .contains("NOT a goodbye"))
        assertTrue(WorkspaceChatTurnFrame.instructions(prior + turn("user", "Sahi hai", 4))
            .contains("NOT a goodbye"))
        val task = listOf(turn("user", "Explain binary search with edge cases.", 5))
        assertFalse(WorkspaceChatTurnFrame.isCasual(task))
        assertEquals("", WorkspaceChatTurnFrame.instructions(task))
        val large = listOf(turn("user", "I am planning a trip. " + "x".repeat(500), 6))
        assertEquals("", WorkspaceChatTurnFrame.instructions(large))
    }

    @Test fun bothFreeProviderRoutesKeepRawUserLastAndTheSameLocalTurnFrame() {
        val messages = listOf(
            turn("user", "My first pottery class is tomorrow. Keep it short please, friend.", 1),
            turn("assistant", "Let's go skydiving!", 2),
            turn("user", "Okay", 3))
        val openRouter = JSONObject(WorkspaceChatGateway.openRouterBody(messages))
        val groq = JSONObject(WorkspaceGroqFree.body(messages))
        val frames = listOf(openRouter, groq).map { it.getJSONArray("messages") }
        val system = frames.first().getJSONObject(0).getString("content")
        assertTrue(system.contains("pottery class"))
        assertFalse(system.contains("skydiving"))
        frames.forEach { entries ->
            assertEquals(system, entries.getJSONObject(0).getString("content"))
            assertEquals(4, entries.length())
            assertEquals(messages.last().text, entries.getJSONObject(3).getString("content"))
            assertEquals(messages[1].text, entries.getJSONObject(2).getString("content"))
        }
    }

    @Test fun longDisconnectedChatReplyIsHeldButValidShortAndTaskRepliesAreNot() {
        val casual = listOf(turn("user",
            "I am starting guitar lessons tomorrow. Please give short replies.", 1))
        val disconnected = "I suggest a completely different event somewhere else entirely. " +
            "You should arrange lots of preparations and make decisions about other things first."
        assertTrue(disconnected.length >= 100)
        val refused = runCatching { WorkspaceChatTurnFrame.verify(casual, disconnected) }.exceptionOrNull()
        assertNotNull(refused)
        assertTrue(refused!!.message!!.contains("not saved"))
        assertEquals("Sounds fun!", WorkspaceChatTurnFrame.verify(casual, "Sounds fun!"))
        val connected = "Guitar lessons sound fun! Are you excited about starting? " +
            "It's totally fine to take your time and learn at your own pace."
        assertEquals(connected, WorkspaceChatTurnFrame.verify(casual, connected))
        val technical = listOf(turn("user", "Explain what recursion is with an example.", 2))
        assertEquals(disconnected, WorkspaceChatTurnFrame.verify(technical, disconnected))
        val withoutShortRequest = listOf(turn("user", "I am starting guitar lessons tomorrow.", 3))
        assertEquals(disconnected, WorkspaceChatTurnFrame.verify(withoutShortRequest, disconnected))
    }

    @Test fun assistantFabricationNeverCountsAsUserTopicEvidence() {
        val messages = listOf(
            turn("user", "I'm training for a swimming competition. Short reply please.", 1),
            turn("assistant", "You are planning a concert in Berlin.", 2),
            turn("user", "Kya hei", 3))
        val invented = "Berlin concert preparation is a very important decision for your imaginary schedule. " +
            "You should first prepare your flights and discuss hotel bookings with everyone."
        assertTrue(WorkspaceChatTurnFrame.instructions(messages).contains("swimming competition"))
        assertFalse(WorkspaceChatTurnFrame.instructions(messages).contains("Berlin"))
        assertNotNull(runCatching { WorkspaceChatTurnFrame.verify(messages, invented) }.exceptionOrNull())
    }
}
