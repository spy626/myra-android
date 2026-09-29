package com.myra.assistant.ui.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Temporary phone probe for a QA handoff:
 * first proposal reaches the requested final marker, then reviewer feedback may add the same-file
 * evidence required by the acceptance invariant before the mandatory second review.
 */
internal object WorkspaceGitHubReviewerRevisionProbe {
    const val REVIEW_MARKER = "REVIEWED_FINAL"
    const val REVIEW_EVIDENCE = "QA_HANDOFF_CONFIRMED"
}

class WorkspaceGitHubReviewerRevisionProbeTest {
    @Test fun reviewerRevisionProbeMustSatisfyMarkerAndHandoffEvidenceTogether() {
        val valid =
            (WorkspaceGitHubReviewerRevisionProbe.REVIEW_MARKER == "BASELINE" &&
                WorkspaceGitHubReviewerRevisionProbe.REVIEW_EVIDENCE == "NONE") ||
            (WorkspaceGitHubReviewerRevisionProbe.REVIEW_MARKER == "REVIEWED_FINAL" &&
                WorkspaceGitHubReviewerRevisionProbe.REVIEW_EVIDENCE == "QA_HANDOFF_CONFIRMED")
        assertTrue(
            "Final reviewed state requires its QA handoff evidence in the same file",
            valid,
        )
    }
}
