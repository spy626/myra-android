package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWorkflowFeedbackGroundingTest {
    private val receipt = WorkspaceRecentGitHubActionReceipt.Receipt(
        repository = "spy626/myra-android",
        branch = "agent/myra-phase-1",
        userTask = "Add correction learning",
        commitSha = "1234567890abcdef1234567890abcdef12345678",
        files = listOf(
            "app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceActivity.kt"
        ),
        ciRunNumber = 3352L,
        ciStatus = "completed",
        ciConclusion = "success",
        ciUrl = "https://github.com/spy626/myra-android/actions/runs/77",
        completedAtMs = 100L,
    )

    @Test fun exactCiMarkerGroundsFeedbackTarget() {
        assertTrue(
            WorkspaceWorkflowFeedbackGrounding.exactReceiptVisible(
                recentAssistantTexts = listOf(
                    "Done bro. CI #3352 GREEN hai."
                ),
                receipt = receipt,
            )
        )
    }

    @Test fun exactRunUrlAlsoGroundsFeedbackTarget() {
        assertTrue(
            WorkspaceWorkflowFeedbackGrounding.exactReceiptVisible(
                recentAssistantTexts = listOf(
                    "Run: https://github.com/spy626/myra-android/actions/runs/77"
                ),
                receipt = receipt,
            )
        )
    }

    @Test fun differentCiOrGenericSuccessDoesNotGroundFeedbackTarget() {
        assertFalse(
            WorkspaceWorkflowFeedbackGrounding.exactReceiptVisible(
                recentAssistantTexts = listOf(
                    "Done bro. CI #3350 GREEN hai.",
                    "Everything passed."
                ),
                receipt = receipt,
            )
        )
    }
}
