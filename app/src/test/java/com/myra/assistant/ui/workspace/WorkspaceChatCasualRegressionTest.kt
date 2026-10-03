package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceChatCasualRegressionTest {
    private fun turn(role: String, text: String, index: Int) =
        WorkspaceConversationStore.Message("casual-$index", role, text, index.toLong())

    private fun chat(vararg text: String): List<WorkspaceConversationStore.Message> =
        text.mapIndexed { index, value -> turn(if (index % 2 == 0) "user" else "assistant", value, index) }

    @Test fun mentionsOfMeetingOtherPeopleAndQuestionsAreNotInvitations() {
        val messages = chat(
            "Kal main library jaane ka plan kar raha hoon. Dost ki tarah short reply dena.",
            "Achha, library jaane ka plan hai.",
            "Sahi hai 😄"
        )
        listOf(
            "Kya library mein tum kisi dost se milne wale ho?",
            "Library mein doston se milna bhi achha lag sakta hai.",
            "Kal kisi se meet karna hai ya akele padhoge?",
            "Kisi ko mil lo kehne ki zaroorat nahi hai."
        ).forEach { reply -> assertEquals(reply, WorkspaceChatTurnFrame.verify(messages, reply)) }
    }

    @Test fun directUninvitedProposalIsRejectedButRealInvitationAllowsIt() {
        val messages = chat(
            "Kal main library jaane ka plan kar raha hoon. Dost ki tarah short reply dena.",
            "Achha, library jaane ka plan hai.",
            "Sahi hai"
        )
        listOf("Kal library mein milte hain!", "Let's meet tomorrow.").forEach { proposal ->
            assertTrue(runCatching { WorkspaceChatTurnFrame.verify(messages, proposal) }.isFailure)
        }
        val invited = listOf(
            turn("user", "LYRA, kal tum mujhse milne aaogi?", 0),
            turn("assistant", "Kya plan hai?", 1),
            turn("user", "Sahi hai", 2)
        )
        assertEquals("Kal library mein milte hain!", WorkspaceChatTurnFrame.verify(invited,
            "Kal library mein milte hain!"))
    }

    @Test fun aNewAcknowledgementCannotSaveWordForWordPreviousAssistantReply() {
        val repeated = "Achha, library ka plan sahi hai. Aaram se padhna."
        val messages = chat(
            "Kal main library jaane ka plan kar raha hoon. Dost ki tarah short reply dena.",
            repeated,
            "Sahi hai 😄"
        )
        val failure = runCatching { WorkspaceChatTurnFrame.verify(messages, repeated) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("repeated its previous reply"))
        assertEquals("Haan, theek hai 😄", WorkspaceChatTurnFrame.verify(messages,
            "Haan, theek hai 😄"))
        val explicitTask = listOf(
            turn("user", "Repeat the following sentence exactly: hello world", 0),
            turn("assistant", "hello world", 1),
            turn("user", "Repeat that answer exactly", 2)
        )
        assertEquals("hello world", WorkspaceChatTurnFrame.verify(explicitTask, "hello world"))
    }

    @Test fun newestUserTurnAndHinglishPromptAreSentAcrossRemainingFreeRoutes() {
        val messages = chat(
            "Kal main library jaane ka plan kar raha hoon. Dost ki tarah short reply dena.",
            "Achha, library wali baat samajh gayi.",
            "Sahi hai 😄"
        )
        val openRouter = JSONObject(WorkspaceChatGateway.openRouterBody(messages))
        val outbound = openRouter.getJSONArray("messages")
        assertEquals("user", outbound.getJSONObject(outbound.length() - 1).getString("role"))
        assertEquals("Sahi hai 😄", outbound.getJSONObject(outbound.length() - 1).getString("content"))
        assertEquals("Achha, library wali baat samajh gayi.",
            outbound.getJSONObject(outbound.length() - 2).getString("content"))
        val instructions = outbound.getJSONObject(0).getString("content")
        assertTrue(instructions.contains("Koi random ya unrelated words insert mat karo"))
        assertTrue(instructions.contains("never repeat the previous assistant answer word-for-word"))
        assertFalse(instructions.contains("Kal studio mein milte hain!"))
        val groq = JSONObject(WorkspaceGroqFree.body(messages)).getJSONArray("messages")
        assertEquals(instructions, groq.getJSONObject(0).getString("content"))
        assertEquals("Sahi hai 😄", groq.getJSONObject(groq.length() - 1).getString("content"))

    }
}
