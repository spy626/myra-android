package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceChatRetryPolicyTest {
    @Test fun zai429NeverOffersImmediateRetryButton() {
        val decision = WorkspaceChatRetryPolicy.decision(
            WorkspaceChatGateway.Provider.ZAI_FREE,
            WorkspaceZaiFree.rateLimitFailure("12"))
        assertFalse(decision.allowImmediateRetry)
        assertTrue(decision.rateLimited)
        assertTrue(decision.note(false).contains("Immediate Retry is disabled"))
        assertTrue(decision.note(false).contains("Nothing is resent automatically"))
    }

    @Test fun timeoutAndOtherProvidersKeepExplicitUserRetryAvailable() {
        val timeout = WorkspaceChatRetryPolicy.decision(
            WorkspaceChatGateway.Provider.ZAI_FREE,
            IllegalStateException("timeout"))
        assertTrue(timeout.allowImmediateRetry)
        val openRouter429 = WorkspaceChatRetryPolicy.decision(
            WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            IllegalStateException("HTTP 429"))
        assertTrue(openRouter429.allowImmediateRetry)
    }
}
