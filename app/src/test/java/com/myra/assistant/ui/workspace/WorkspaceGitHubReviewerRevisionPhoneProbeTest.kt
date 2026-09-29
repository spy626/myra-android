package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubReviewerRevisionPhoneProbeTest {
    @Test fun onlyFirstCandidateProbeIsForcedToRevise() {
        val accepted = WorkspaceGitHubPatchReviewer.Review(
            WorkspaceGitHubPatchReviewer.Decision.ACCEPT,
            "provider accepted",
            emptyList(),
        )
        val candidate = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange(
                    WorkspaceGitHubReviewerRevisionPhoneProbe.TARGET_PATH,
                    """internal object Probe { const val REVIEW_MARKER = "CANDIDATE" }""",
                )
            ),
            rationale = "probe",
        )

        val forced = WorkspaceGitHubReviewerRevisionPhoneProbe.adjust(
            reviewRevisionAttempt = 0,
            prepared = candidate,
            review = accepted,
        )
        assertEquals(WorkspaceGitHubPatchReviewer.Decision.REVISE, forced.decision)
        assertTrue(forced.summary.contains("REVIEWED_FINAL"))

        val second = WorkspaceGitHubReviewerRevisionPhoneProbe.adjust(
            reviewRevisionAttempt = 1,
            prepared = candidate,
            review = accepted,
        )
        assertEquals(WorkspaceGitHubPatchReviewer.Decision.ACCEPT, second.decision)

        val final = candidate.copy(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange(
                    WorkspaceGitHubReviewerRevisionPhoneProbe.TARGET_PATH,
                    """internal object Probe { const val REVIEW_MARKER = "REVIEWED_FINAL" }""",
                )
            )
        )
        val untouched = WorkspaceGitHubReviewerRevisionPhoneProbe.adjust(
            reviewRevisionAttempt = 0,
            prepared = final,
            review = accepted,
        )
        assertEquals(WorkspaceGitHubPatchReviewer.Decision.ACCEPT, untouched.decision)
    }
}
