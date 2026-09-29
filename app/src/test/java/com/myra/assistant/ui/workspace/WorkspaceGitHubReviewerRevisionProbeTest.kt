package com.myra.assistant.ui.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Temporary physical-phone probe for reviewer REVISE -> same-coder revision -> second review.
 *
 * INITIAL is the clean setup state. NEEDS_REVIEW is intentionally invalid as a final committed
 * state. REVIEWED is the expected state after the bounded reviewer-requested revision.
 */
internal object WorkspaceGitHubReviewerRevisionProbe {
    const val PROBE_STATE = "REVIEWED"
}

class WorkspaceGitHubReviewerRevisionProbeTest {
    @Test fun reviewerRevisionProbeMustNotCommitFirstStageState() {
        assertTrue(
            "Reviewer revision probe must finish INITIAL or REVIEWED, never NEEDS_REVIEW",
            WorkspaceGitHubReviewerRevisionProbe.PROBE_STATE in setOf("INITIAL", "REVIEWED"),
        )
    }
}
