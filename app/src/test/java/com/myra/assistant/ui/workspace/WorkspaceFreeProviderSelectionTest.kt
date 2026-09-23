package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceFreeProviderSelectionTest {
    @Test fun ordinaryChatUsesOnlyGroqOrOpenRouter() {
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
