package com.myra.assistant.ui.workspace

/**
 * Temporary physical-phone acceptance instrumentation for the generic reviewer revision loop.
 *
 * This models a QA handoff: the user's final marker remains unchanged across the revision, while
 * the first reviewer asks for one same-file acceptance-evidence correction. It never grants write
 * authority and never forces ACCEPT. Remove with the probe after physical-phone acceptance.
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
                """const val REVIEW_MARKER = "REVIEWED_FINAL""""
            ) ||
            !prepared.files.single().content.contains(
                """const val REVIEW_EVIDENCE = "NONE""""
            )
        ) {
            return review
        }
        return WorkspaceGitHubPatchReviewer.Review(
            decision = WorkspaceGitHubPatchReviewer.Decision.REVISE,
            summary =
                "Controlled QA handoff: the requested REVIEW_MARKER is correct, but the existing " +
                    "same-file acceptance invariant also requires REVIEW_EVIDENCE to be " +
                    "QA_HANDOFF_CONFIRMED before commit. Keep REVIEW_MARKER unchanged and update " +
                    "only that evidence value.",
            risks = listOf(
                "Committing REVIEWED_FINAL with REVIEW_EVIDENCE=NONE would fail the existing test invariant."
            ),
        )
    }
}
