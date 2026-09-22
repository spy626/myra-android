package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceChatRecallGroundingTest {
    private fun u(text: String) = WorkspaceConversationStore.Message("u-$text", "user", text, 1)
    private fun a(text: String) = WorkspaceConversationStore.Message("a-$text", "assistant", text, 2)

    @Test fun explicitRecallQuotesOnlyPreviousUserNotAssistantGuess() {
        val messages = listOf(
            u("Kal main beach jaane ka plan kar raha hoon. Mujhse dost ki tarah normal baat karo, short reply dena."),
            a("You mentioned a trip to Manali."),
            u("Maine kal kahan jaane ka plan bataya tha?"))
        val reply = requireNotNull(WorkspaceChatRecallGrounding.answer(messages))
        assertEquals("Tumne kaha tha: “Kal main beach jaane ka plan kar raha hoon.”", reply)
        assertFalse(reply.contains("Manali"))
    }

    @Test fun worksForDifferentTopicsAndEnglishWithoutPlaceSpecificRules() {
        val history = listOf(u("My friend's name is Kareem and we study together."),
            a("I think the name is Rehan."), u("What did I say about my friend?"))
        assertEquals("You said: “My friend's name is Kareem and we study together.”",
            WorkspaceChatRecallGrounding.answer(history))
        val recipe = listOf(u("I added cardamom to the tea."), a("Nice!"),
            u("What did I tell you about the tea?"))
        assertEquals("You said: “I added cardamom to the tea.”",
            WorkspaceChatRecallGrounding.answer(recipe))
    }

    @Test fun unrelatedRecentUserMessageDoesNotDisplaceRelevantEarlierEvidence() {
        val history = listOf(u("My project is called Bluebird."), a("Cool."),
            u("How is your day?"), a("Good."), u("What did I say about my project?"))
        assertEquals("You said: “My project is called Bluebird.”",
            WorkspaceChatRecallGrounding.answer(history))
    }

    @Test fun competingUserClaimsAskForClarificationInsteadOfPickingAnEntity() {
        val history = listOf(u("Kal park jaane ka plan hai."),
            u("Kal museum jaane ka plan hai."), u("Maine kal kahan jaane ka plan bataya tha?"))
        val answer = requireNotNull(WorkspaceChatRecallGrounding.answer(history))
        assertTrue(answer.contains("ek se zyada"))
        assertFalse(answer.contains("park"))
        assertFalse(answer.contains("museum"))
    }

    @Test fun missingEvidenceDoesNotInventAndNormalTurnsStillUseProvider() {
        assertTrue(requireNotNull(WorkspaceChatRecallGrounding.answer(listOf(
            u("Maine kal kahan jaane ka plan bataya tha?")))).contains("guess nahi"))
        assertTrue(requireNotNull(WorkspaceChatRecallGrounding.answer(listOf(
            u("Hello"), u("Maine kal kahan jaane ka plan bataya tha?")))).contains("guess nahi"))
        assertNull(WorkspaceChatRecallGrounding.answer(listOf(u("Kal main beach jaane ka plan kar raha hoon."))))
        assertNull(WorkspaceChatRecallGrounding.answer(listOf(
            u("Maine kal beach jaane ka plan bataya tha."))))
        assertNull(WorkspaceChatRecallGrounding.answer(listOf(
            u("How should I tell my friend about the trip?"))))
    }

    @Test fun sameChatRequestCarriesLiteralUserEvidenceAndGenericDisciplineToOpenRouter() {
        val conversation = listOf(u("I added cardamom to the tea."),
            a("You added cinnamon."), u("What did I tell you about the tea?"))
        val json = JSONObject(WorkspaceChatGateway.openRouterBody(conversation))
        val outgoing = json.getJSONArray("messages")
        val instruction = outgoing.getJSONObject(0).getString("content")
        assertTrue(instruction.contains("assistant replies can be mistaken"))
        assertTrue(instruction.contains("earlier USER turns"))
        assertEquals("I added cardamom to the tea.", outgoing.getJSONObject(1).getString("content"))
        assertEquals("What did I tell you about the tea?", outgoing.getJSONObject(outgoing.length() - 1).getString("content"))
        assertFalse(instruction.contains("cinnamon"))
    }
}
