package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceFreeProviderSelectionTest {
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
