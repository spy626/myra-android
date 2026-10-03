package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubReviewerRevisionPhoneProbeTest {
    private fun prepared(marker: String, evidence: String) =
        WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange(
                    WorkspaceGitHubReviewerRevisionPhoneProbe.TARGET_PATH,
                    """internal object Probe {
    const val REVIEW_MARKER = "$marker"
    const val REVIEW_EVIDENCE = "$evidence"
}""",
                )
            ),
            rationale = "probe",
        )

    @Test fun firstFinalMarkerWithoutEvidenceGetsOneQaHandoffRevision() {
        val accepted = WorkspaceGitHubPatchReviewer.Review(
            WorkspaceGitHubPatchReviewer.Decision.ACCEPT,
            "provider accepted",
            emptyList(),
        )

        val forced = WorkspaceGitHubReviewerRevisionPhoneProbe.adjust(
            reviewRevisionAttempt = 0,
            prepared = prepared("REVIEWED_FINAL", "NONE"),
            review = accepted,
        )
        assertEquals(WorkspaceGitHubPatchReviewer.Decision.REVISE, forced.decision)
        assertTrue(forced.summary.contains("QA_HANDOFF_CONFIRMED"))
        assertTrue(forced.summary.contains("Keep REVIEW_MARKER unchanged"))

        val second = WorkspaceGitHubReviewerRevisionPhoneProbe.adjust(
            reviewRevisionAttempt = 1,
            prepared = prepared("REVIEWED_FINAL", "NONE"),
            review = accepted,
        )
        assertEquals(WorkspaceGitHubPatchReviewer.Decision.ACCEPT, second.decision)

        val complete = WorkspaceGitHubReviewerRevisionPhoneProbe.adjust(
            reviewRevisionAttempt = 0,
            prepared = prepared("REVIEWED_FINAL", "QA_HANDOFF_CONFIRMED"),
            review = accepted,
        )
        assertEquals(WorkspaceGitHubPatchReviewer.Decision.ACCEPT, complete.decision)
    }
}
