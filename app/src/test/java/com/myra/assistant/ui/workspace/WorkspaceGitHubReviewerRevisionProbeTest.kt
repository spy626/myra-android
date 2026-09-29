package com.myra.assistant.ui.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Temporary physical-phone probe for reviewer REVISE -> same-coder revision -> second review.
 *
 * BASELINE is the clean setup state. NEEDS_REVIEW is intentionally invalid as a final committed
 * state. REVIEWED_FINAL is the expected state after the bounded reviewer-requested revision.
 */
internal object WorkspaceGitHubReviewerRevisionProbe {
    const val PROBE_STATE = "BASELINE"
}

class WorkspaceGitHubReviewerRevisionProbeTest {
    @Test fun reviewerRevisionProbeMustNotCommitFirstStageState() {
        assertTrue(
            "Reviewer revision probe must finish BASELINE or REVIEWED_FINAL, never NEEDS_REVIEW",
            WorkspaceGitHubReviewerRevisionProbe.PROBE_STATE in setOf("BASELINE", "REVIEWED_FINAL"),
        )
    }
}
