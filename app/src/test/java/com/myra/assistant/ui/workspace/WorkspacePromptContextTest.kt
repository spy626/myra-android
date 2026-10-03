package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspacePromptContextTest {
    private fun user(text: String) = WorkspaceConversationStore.Message("u", "user", text, 1L)
    private fun assistant(text: String) = WorkspaceConversationStore.Message("a", "assistant", text, 2L)
    private fun entries(messages: List<WorkspaceConversationStore.Message>) =
        JSONObject(WorkspaceChatGateway.openRouterBody(messages)).getJSONArray("messages")

    @Test fun ambiguousHinglishWithoutEvidenceAsksRatherThanInventsCompany() {
        val prompt = user("Mujhe ek ai companiyon banane hai mujhe prompt do")
        assertEquals(WorkspacePromptContext.Decision.CLARIFY,
            WorkspacePromptContext.resolve(listOf(prompt)))
        val sent = entries(listOf(prompt))
        assertEquals("system", sent.getJSONObject(0).getString("role"))
        val instruction = sent.getJSONObject(0).getString("content")
        assertTrue(instruction.contains("Ask only one concise clarification"))
        assertFalse(instruction.contains("market analysis"))
        assertEquals(prompt.text, sent.getJSONObject(1).getString("content"))
    }

    @Test fun pastExplicitCompanionInSameChatResolvesAmbiguityEvenWhenOlderThanRecentWindow() {
        val earlier = user("Mujhe AI companion app chahiye with voice and memory")
        val filler = (1..13).flatMap { listOf(user("Other topic $it"), assistant("Okay $it")) }
        val latest = user("Mujhe ek ai companiyon banane hai mujhe prompt do")
        val history = listOf(earlier) + filler + latest
        assertEquals(WorkspacePromptContext.Decision.BUILD_COMPANION,
            WorkspacePromptContext.resolve(history))
        val sent = entries(history)
        assertEquals(25, sent.length()) // one context instruction + latest 24 raw turns
        val instruction = sent.getJSONObject(0).getString("content")
        assertTrue(instruction.contains("CODING/DEVELOPMENT AI"))
        assertTrue(instruction.contains("same conversation"))
        assertFalse(sent.toString().contains(earlier.text)) // old raw message is projected, not replayed verbatim
        assertEquals(latest.text, sent.getJSONObject(sent.length() - 1).getString("content"))
    }

    @Test fun explicitCompanyAndBusinessContextAreNotHijackedIntoCompanion() {
        assertNull(WorkspacePromptContext.resolve(listOf(user("AI company ka business plan do"))))
        val messages = listOf(user("Mujhe AI company ka startup banana hai"),
            user("AI companiyon banane ka prompt do"))
        assertEquals(WorkspacePromptContext.Decision.BUSINESS_COMPANY,
            WorkspacePromptContext.resolve(messages))
        assertFalse(entries(messages).getJSONObject(0).getString("content")
            .contains("CODING/DEVELOPMENT AI"))
        val explicit = listOf(user("Mujhe AI companion app chahiye"),
            user("Ab AI company ka business plan do"))
        assertNull(WorkspacePromptContext.resolve(explicit))
    }

    @Test fun appCuesResolveWithoutPastChatAndFollowUpUsesLatestUserTopic() {
        val withApp = listOf(user("AI companiyon Android app banane ka prompt do"))
        assertEquals(WorkspacePromptContext.Decision.BUILD_COMPANION,
            WorkspacePromptContext.resolve(withApp))
        val followUp = listOf(user("AI companion app banane ka idea hai"),
            assistant("Sure"), user("Woh prompt do"))
        assertEquals(WorkspacePromptContext.Decision.BUILD_COMPANION,
            WorkspacePromptContext.resolve(followUp))
        val changed = listOf(user("AI companion app"), user("AI company business"),
            user("Woh prompt do"))
        assertEquals(WorkspacePromptContext.Decision.BUSINESS_COMPANY,
            WorkspacePromptContext.resolve(changed))
    }

    @Test fun unrelatedChatsStayUnchangedAndAssistantsCannotSupplyIntentEvidence() {
        assertNull(WorkspacePromptContext.resolve(listOf(user("Hi bro"))))
        assertEquals("user", entries(listOf(user("Hi bro"))).getJSONObject(1).getString("role"))
        assertEquals(WorkspacePromptContext.Decision.CLARIFY,
            WorkspacePromptContext.resolve(listOf(assistant("You want an AI companion"),
                user("AI companiyon banane ka prompt do"))))
        assertNull(WorkspacePromptContext.resolve(listOf(user("Horror story do"))))
        assertNull(WorkspacePromptContext.resolve(listOf(user("Write Python script"))))
    }
}
