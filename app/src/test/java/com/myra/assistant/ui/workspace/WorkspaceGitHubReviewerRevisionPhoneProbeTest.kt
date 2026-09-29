package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceGitHubReviewerRevisionPhoneProbeTest {
    @Test fun onlyFirstNeedsReviewProbeIsForcedToRevise() {
        val accepted = WorkspaceGitHubPatchReviewer.Review(
            WorkspaceGitHubPatchReviewer.Decision.ACCEPT,
            "provider accepted",
            emptyList(),
        )
        val needsReview = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange(
                    WorkspaceGitHubReviewerRevisionPhoneProbe.TARGET_PATH,
                    """internal object Probe { const val PROBE_STATE = "NEEDS_REVIEW" }""",
                )
            ),
            rationale = "probe",
        )
        val forced = WorkspaceGitHubReviewerRevisionPhoneProbe.adjust(
            reviewRevisionAttempt = 0,
            prepared = needsReview,
            review = accepted,
        )
        assertEquals(WorkspaceGitHubPatchReviewer.Decision.REVISE, forced.decision)
        assertEquals(true, forced.summary.contains("REVIEWED_FINAL"))

        val second = WorkspaceGitHubReviewerRevisionPhoneProbe.adjust(
            reviewRevisionAttempt = 1,
            prepared = needsReview,
            review = accepted,
        )
        assertEquals(WorkspaceGitHubPatchReviewer.Decision.ACCEPT, second.decision)

        val reviewedFinal = needsReview.copy(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange(
                    WorkspaceGitHubReviewerRevisionPhoneProbe.TARGET_PATH,
                    """internal object Probe { const val PROBE_STATE = "REVIEWED_FINAL" }""",
                )
            )
        )
        val untouched = WorkspaceGitHubReviewerRevisionPhoneProbe.adjust(
            reviewRevisionAttempt = 0,
            prepared = reviewedFinal,
            review = accepted,
        )
        assertEquals(WorkspaceGitHubPatchReviewer.Decision.ACCEPT, untouched.decision)
    }
}
