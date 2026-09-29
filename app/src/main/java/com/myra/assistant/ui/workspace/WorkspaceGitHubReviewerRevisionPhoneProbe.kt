package com.myra.assistant.ui.workspace

/**
 * Temporary physical-phone acceptance instrumentation for the reviewer revision loop.
 *
 * It never grants write authority and never forces ACCEPT. For one exact test file only, the first
 * successfully parsed review of the NEEDS_REVIEW probe state is converted to a bounded REVISE so
 * the same-coder revision + mandatory second-review path can be exercised on a real phone.
 *
 * Remove this object together with WorkspaceGitHubReviewerRevisionProbeTest after phone acceptance.
 */
internal object WorkspaceGitHubReviewerRevisionPhoneProbe {
    const val TARGET_PATH =
        "app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceGitHubReviewerRevisionProbeTest.kt"

    fun adjust(
        reviewRevisionAttempt: Int,
        prepared: WorkspaceGitHubSelfEditBatch.Prepared,
        review: WorkspaceGitHubPatchReviewer.Review,
    ): WorkspaceGitHubPatchReviewer.Review {
        if (reviewRevisionAttempt != 0 ||
            prepared.files.size != 1 ||
            prepared.files.single().path != TARGET_PATH ||
            !prepared.files.single().content.contains(
                """const val PROBE_STATE = "NEEDS_REVIEW""""
            )
        ) {
            return review
        }
        return WorkspaceGitHubPatchReviewer.Review(
            decision = WorkspaceGitHubPatchReviewer.Decision.REVISE,
            summary =
                "Controlled phone probe: revise PROBE_STATE from the requested first-stage " +
                    "NEEDS_REVIEW value to REVIEWED, preserving the same file and test invariant.",
            risks = listOf(
                "NEEDS_REVIEW is intentionally not an acceptable final probe state."
            ),
        )
    }
}
