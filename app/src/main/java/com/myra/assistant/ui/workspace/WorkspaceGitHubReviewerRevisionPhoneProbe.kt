package com.myra.assistant.ui.workspace

/**
 * Temporary physical-phone acceptance instrumentation for the generic reviewer revision loop.
 *
 * It never grants write authority and never forces ACCEPT. For one exact test-only file, the first
 * successfully parsed review of CANDIDATE is converted to a bounded REVISE asking for the valid
 * final marker. The original phone-test instruction explicitly authorizes that reviewer correction.
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
                """const val REVIEW_MARKER = "CANDIDATE""""
            )
        ) {
            return review
        }
        return WorkspaceGitHubPatchReviewer.Review(
            decision = WorkspaceGitHubPatchReviewer.Decision.REVISE,
            summary =
                "Controlled phone probe: the candidate marker is intentionally not a valid final " +
                    "state. Keep the same requested test-only scope and revise REVIEW_MARKER to " +
                    "REVIEWED_FINAL so the existing invariant remains valid.",
            risks = listOf(
                "CANDIDATE is not an allowed final marker in the existing test invariant."
            ),
        )
    }
}
