package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceFreeProviderSelectionTest {
    @Test fun researchPrefersApprovedGroqButNeverUsesStickyLlm7() {
        assertEquals(WorkspaceChatGateway.Provider.GROQ_FREE,
            WorkspaceFreeProviderSelection.chooseResearch(
                openRouterReady = true, groqReady = true,
                groqFreeZdrApproved = true, groqFitsResearchEnvelope = true))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.chooseResearch(
                openRouterReady = true, groqReady = true,
                groqFreeZdrApproved = false, groqFitsResearchEnvelope = true))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.chooseResearch(
                openRouterReady = true, groqReady = true,
                groqFreeZdrApproved = true, groqFitsResearchEnvelope = false))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.chooseResearch(
                openRouterReady = true, groqReady = false,
                groqFreeZdrApproved = true, groqFitsResearchEnvelope = true))
        assertNull(WorkspaceFreeProviderSelection.chooseResearch(
            openRouterReady = false, groqReady = true,
            groqFreeZdrApproved = false, groqFitsResearchEnvelope = true))
    }

    @Test fun groqResearchPreflightUsesExactPublicEnvelope() {
        val user = WorkspaceConversationStore.Message(
            "research-turn", "user",
            "QUESTION: Compare Android and iOS battery settings. E1: Android battery settings. E2: iOS battery settings.",
            1L)
        val exactlySent = listOf(user)
        assertEquals(true, WorkspaceGroqFree.withinBudget(
            exactlySent, WorkspaceResearchSynthesis.SYSTEM_INSTRUCTIONS))
        val oversized = listOf(user.copy(text = "Z".repeat(WorkspaceGroqFree.MAX_PROMPT_CHARS)))
        assertEquals(false, WorkspaceGroqFree.withinBudget(
            oversized, WorkspaceResearchSynthesis.SYSTEM_INSTRUCTIONS))
    }

    @Test fun ordinaryChatKeepsExistingGroqOrOpenRouterWhenLlm7IsOff() {
        assertEquals(WorkspaceChatGateway.Provider.GROQ_FREE,
            WorkspaceFreeProviderSelection.choose(
                openRouterAvailable = true,
                groqAvailable = true,
                groqFreeZdrApproved = true,
                groqWithinBudget = true,
                hasAttachments = false))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(
                openRouterAvailable = true,
                groqAvailable = false,
                groqFreeZdrApproved = false,
                groqWithinBudget = false,
                hasAttachments = false))
        assertNull(WorkspaceFreeProviderSelection.choose(
            openRouterAvailable = false,
            groqAvailable = false,
            groqFreeZdrApproved = false,
            groqWithinBudget = false,
            hasAttachments = false))
    }
 
    @Test fun llm7OptInIsStickyPrimaryTextRoute() {
        assertEquals(WorkspaceChatGateway.Provider.LLM7_FREE,
            WorkspaceFreeProviderSelection.choose(
                openRouterAvailable = true,
                groqAvailable = true,
                groqFreeZdrApproved = true,
                groqWithinBudget = true,
                hasAttachments = false,
                llm7Available = true,
                llm7Approved = true,
                llm7WithinBudget = true))
        assertNull(WorkspaceFreeProviderSelection.choose(
            openRouterAvailable = true,
            groqAvailable = true,
            groqFreeZdrApproved = true,
            groqWithinBudget = true,
            hasAttachments = false,
            llm7Available = false,
            llm7Approved = true,
            llm7WithinBudget = true))
        assertNull(WorkspaceFreeProviderSelection.choose(
            openRouterAvailable = true,
            groqAvailable = true,
            groqFreeZdrApproved = true,
            groqWithinBudget = true,
            hasAttachments = false,
            llm7Available = true,
            llm7Approved = true,
            llm7WithinBudget = false))
    }

    @Test fun attachmentsStayOnOpenRouterOrLocal() {
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(
                openRouterAvailable = true,
                groqAvailable = true,
                groqFreeZdrApproved = true,
                groqWithinBudget = true,
                hasAttachments = true))
        assertNull(WorkspaceFreeProviderSelection.choose(
            openRouterAvailable = false,
            groqAvailable = true,
            groqFreeZdrApproved = true,
            groqWithinBudget = true,
            hasAttachments = true))
    }
}
