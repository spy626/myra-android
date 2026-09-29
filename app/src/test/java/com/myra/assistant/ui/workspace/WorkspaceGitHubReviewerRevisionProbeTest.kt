package com.myra.assistant.ui.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Temporary phone probe for generic reviewer REVISE -> coder revision -> second review.
 *
 * BASELINE is the clean repository state. CANDIDATE is intentionally reviewable but not a valid
 * final committed state. REVIEWED_FINAL is the valid post-review final state.
 */
internal object WorkspaceGitHubReviewerRevisionProbe {
    const val REVIEW_MARKER = "BASELINE"
}

class WorkspaceGitHubReviewerRevisionProbeTest {
    @Test fun reviewerRevisionProbeMustEndInAValidState() {
        assertTrue(
            "Reviewer probe must finish BASELINE or REVIEWED_FINAL, never CANDIDATE",
            WorkspaceGitHubReviewerRevisionProbe.REVIEW_MARKER in
                setOf("BASELINE", "REVIEWED_FINAL"),
        )
    }
}
