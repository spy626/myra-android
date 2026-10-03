package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWorkflowFeedbackIntentTest {
    @Test fun feedbackRequiresExactRecentReceiptGrounding() {
        assertNull(
            WorkspaceWorkflowFeedbackIntent.decide(
                raw = "haan ye sahi tha",
                exactTargetVisibleInSelectedChat = false,
            )
        )
    }

    @Test fun groundedShortConfirmationBecomesSupportingFeedback() {
        val decision = WorkspaceWorkflowFeedbackIntent.decide(
            raw = "haan sahi tha",
            exactTargetVisibleInSelectedChat = true,
        )

        assertEquals(WorkspaceWorkflowFeedbackIntent.Kind.CONFIRM, decision?.kind)
        assertTrue((decision?.confidence ?: 0.0) >= 0.90)
    }

    @Test fun groundedCorrectionAndUndoBecomeCounterFeedbackKinds() {
        assertEquals(
            WorkspaceWorkflowFeedbackIntent.Kind.CORRECT,
            WorkspaceWorkflowFeedbackIntent.decide(
                raw = "ye change galat tha",
                exactTargetVisibleInSelectedChat = true,
            )?.kind,
        )
        assertEquals(
            WorkspaceWorkflowFeedbackIntent.Kind.UNDO,
            WorkspaceWorkflowFeedbackIntent.decide(
                raw = "undo last change",
                exactTargetVisibleInSelectedChat = true,
            )?.kind,
        )
    }

    @Test fun possibleSecretFeedbackIsNotLearned() {
        assertNull(
            WorkspaceWorkflowFeedbackIntent.decide(
                raw = "api_key=sk-12345678901234567890 ye galat tha",
                exactTargetVisibleInSelectedChat = true,
            )
        )
    }
}
