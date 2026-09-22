package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceProviderSelectionTest {
    @Test fun groqTextChosenWhenApprovedAndWithinBudget() {
        assertEquals(WorkspaceChatGateway.Provider.GROQ_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, false))
    }
    @Test fun openRouterChosenWhenGroqUnavailableOrUnapproved() {
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, false, true, false))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, false, false))
    }
    @Test fun absentKeysNeverSelectDeletedRoute() {
        assertNull(WorkspaceFreeProviderSelection.choose(false, false, false, false, false))
    }
    @Test fun photosNeverGoToGroq() {
        assertNull(WorkspaceFreeProviderSelection.choose(false, true, true, true, true))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, true))
    }
}
