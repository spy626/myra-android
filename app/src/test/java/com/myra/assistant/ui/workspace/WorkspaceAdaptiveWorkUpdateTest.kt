package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceAdaptiveWorkUpdateTest {
    private fun prepared(rationale: String) = WorkspaceGitHubSelfEditBatch.Prepared(
        files = listOf(
            WorkspaceGitHubWritePolicy.FileChange(
                "app/src/test/java/com/myra/assistant/ReadingTrackerSafetyTest.kt",
                "class ReadingTrackerSafetyTest",
            )
        ),
        rationale = rationale,
    )

    @Test fun proposalUsesSituationSpecificRationaleInsteadOfFixedDetail() {
        val first = WorkspaceAdaptiveWorkUpdate.proposal(
            prepared("Exact-turn test comment hata kar requested cleanup rakha gaya.")
        )
        val second = WorkspaceAdaptiveWorkUpdate.proposal(
            prepared("Null guard add karke crash path ko bounded kiya gaya.")
        )

        assertEquals("Prepared a change proposal", first.label)
        assertTrue(first.detail.orEmpty().contains("Exact-turn test comment"))
        assertTrue(second.detail.orEmpty().contains("Null guard"))
        assertFalse(first.detail == second.detail)
    }

    @Test fun reviewerDecisionCarriesActualReviewReason() {
        val update = WorkspaceAdaptiveWorkUpdate.review(
            WorkspaceGitHubPatchReviewer.Review(
                decision = WorkspaceGitHubPatchReviewer.Decision.REVISE,
                summary = "Revision repeated the rejected proposal instead of fixing the guard.",
                risks = listOf("The requested correction is still missing."),
            ),
            afterRevision = false,
        )

        assertEquals("Review found a fixable issue", update.label)
        assertTrue(update.detail.orEmpty().contains("repeated the rejected proposal"))
        assertTrue(update.detail.orEmpty().contains("requested correction"))
    }

    @Test fun internalProviderNoiseIsNotShownAsEvidence() {
        val update = WorkspaceAdaptiveWorkUpdate.proposal(
            prepared("xKiro provider qwen produced this patch.")
        )

        assertNotNull(update.detail)
        assertFalse(update.detail.orEmpty().contains("xKiro", ignoreCase = true))
        assertFalse(update.detail.orEmpty().contains("qwen", ignoreCase = true))
        assertTrue(update.detail.orEmpty().contains("ReadingTrackerSafetyTest.kt"))
    }

    @Test fun exactCiUpdatesAreQualifiedToTheObservedRun() {
        val running = WorkspaceAdaptiveWorkUpdate.ciRunning(3288L, "in_progress")
        val passed = WorkspaceAdaptiveWorkUpdate.ciPassed(3288L)
        val failed = WorkspaceAdaptiveWorkUpdate.ciFailed(
            3288L,
            "Unit tests failed in WorkspaceWorkNarrationTest.",
        )

        assertEquals("CI #3288 is in progress", running.label)
        assertEquals("CI #3288 passed for this commit", passed.label)
        assertTrue(passed.detail.orEmpty().contains("Configured build/tests"))
        assertEquals("CI #3288 failed; checking the cause", failed.label)
        assertTrue(failed.detail.orEmpty().contains("WorkspaceWorkNarrationTest"))
    }

    @Test fun untrustedProposalCannotClaimCiSuccess() {
        val update = WorkspaceAdaptiveWorkUpdate.proposal(
            prepared("Change ready hai aur CI #9999 GREEN ho gaya.")
        )

        assertFalse(update.detail.orEmpty().contains("CI #9999"))
        assertTrue(update.detail.orEmpty().contains("ReadingTrackerSafetyTest.kt"))
    }

    @Test fun secretLikeEvidenceIsDropped() {
        val update = WorkspaceAdaptiveWorkUpdate.proposal(
            prepared("api_key=sk-super-secret-123456 should never be shown")
        )
        assertFalse(update.detail.orEmpty().contains("super-secret"))
    }
}
