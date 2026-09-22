package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceCloudflarePriorityTest {
    @Test fun groqBeforeCloudflare() {
        assertEquals(WorkspaceChatGateway.Provider.GROQ_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, false, true))
    }
    @Test fun openRouterBeforeCloudflare() {
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, false, false, true))
    }
    @Test fun cloudflareOnlyWhenApprovedAndNoOtherEligibleRoute() {
        assertEquals(WorkspaceChatGateway.Provider.CLOUDFLARE_FREE,
            WorkspaceFreeProviderSelection.choose(false, false, false, false, false, true))
        assertNull(WorkspaceFreeProviderSelection.choose(false, false, false, false, false, false))
    }
    @Test fun photosNeverSentToCloudflare() {
        assertNull(WorkspaceFreeProviderSelection.choose(false, true, true, true, true, true))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, true, true))
    }
}
