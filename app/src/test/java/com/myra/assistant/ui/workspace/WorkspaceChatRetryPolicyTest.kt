package com.myra.assistant.ui.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceChatRetryPolicyTest {
    @Test fun ordinaryChatFailureOffersOnlyExplicitUserRetry() {
        val decision = WorkspaceChatRetryPolicy.decision()
        assertTrue(decision.allowImmediateRetry)
        assertTrue(decision.note(false).contains("Retry the same complete message"))
        assertTrue(decision.note(true).contains("attachments"))
        assertTrue(decision.note(false).contains("No paid fallback"))
    }
}
