package com.myra.assistant.agent

import com.myra.assistant.screen.RenderedBrowserNavigationPolicy
import org.junit.Assert.*
import org.junit.Test

class BrowserNavigationTaskEvidenceTest {
    private fun intent() = StructuredAgentIntent(
        turnIntent = TurnIntent.ACTION_REQUEST,
        originalUtterance = "Click the Release notes link",
        interpretedGoal = "BROWSER_NAMED_LINK",
        requiresAction = true,
        requiredCapabilities = setOf(ToolCapability.ACCESSIBILITY_CLICK),
        confidence = .95,
    )
    @Test fun stableVisualEvidenceNeverPromotesUnknownDestinationToGoalSuccess() {
        val e = BrowserNavigationTaskEvidence.afterTap(
            RenderedBrowserNavigationPolicy.Verification.BROWSER_CONTENT_CHANGED_URL_UNVERIFIED)
        assertEquals(GeneralVerificationStatus.UNKNOWN, e.generalStatus)
        assertEquals(TaskCompletionState.UNKNOWN, e.taskState)
        assertFalse(e.destinationVerified)
        assertFalse(e.permitsAutonomousContinuation)
        assertTrue(e.observed.contains("two_fresh_browser_observations"))
        assertFalse(e.observed.contains("Release notes"))
    }

    @Test fun rejectedTapIsFailureUnknownPostTapRemainsNonSuccess() {
        val rejected = BrowserNavigationTaskEvidence.rejected()
        assertEquals(GeneralVerificationStatus.FAILURE, rejected.generalStatus)
        assertEquals(TaskCompletionState.FAILURE, rejected.taskState)
        val missing = BrowserNavigationTaskEvidence.afterTap(
            RenderedBrowserNavigationPolicy.Verification.UNKNOWN)
        assertEquals(GeneralVerificationStatus.UNKNOWN, missing.generalStatus)
        assertEquals(TaskCompletionState.UNKNOWN, missing.taskState)
        assertFalse(missing.permitsAutonomousContinuation)
    }

    @Test fun evidenceFeedsOnlyExistingGeneralAndWorkingTaskOwnersOnce() {
        val runtime = GeneralAgentRuntime(now = { 2_000L })
        val working = WorkingTaskContextStore(now = { 2_000L })
        val started = requireNotNull(runtime.start(71, intent(), "browser-71"))
        working.syncRuntime(started)
        val evidence = BrowserNavigationTaskEvidence.afterTap(
            RenderedBrowserNavigationPolicy.Verification.BROWSER_CONTENT_CHANGED_URL_UNVERIFIED)
        assertTrue(BrowserNavigationTaskEvidence.completeOwned(
            71, "browser-71", evidence, runtime, working))
        assertNull(runtime.activeTask())
        assertEquals(AgentRuntimeStatus.NEEDS_CLARIFICATION,
            runtime.lastCompletedTask()?.status)
        assertEquals(TaskCompletionState.UNKNOWN,
            working.snapshot().lastCompletedTask?.completionState)
        assertEquals(evidence.observed, working.snapshot().lastCompletedTask?.observedOutcome)
        assertNull(working.snapshot().taskId)
        assertFalse(BrowserNavigationTaskEvidence.completeOwned(
            71, "browser-71", evidence, runtime, working))
    }

    @Test fun oldTurnCannotOverwriteNewerTaskOrMarkItCompleted() {
        val runtime = GeneralAgentRuntime(now = { 5_000L })
        val working = WorkingTaskContextStore(now = { 5_000L })
        runtime.start(71, intent(), "old")
        val current = requireNotNull(runtime.start(72, intent(), "new"))
        working.syncRuntime(current)
        assertFalse(BrowserNavigationTaskEvidence.completeOwned(
            71, "old", BrowserNavigationTaskEvidence.rejected(), runtime, working))
        assertFalse(BrowserNavigationTaskEvidence.completeOwned(
            72, "wrong", BrowserNavigationTaskEvidence.rejected(), runtime, working))
        assertEquals("new", runtime.activeTask()?.id)
        assertNull(working.snapshot().lastCompletedTask)
        assertTrue(BrowserNavigationTaskEvidence.completeOwned(
            72, "new", BrowserNavigationTaskEvidence.rejected(), runtime, working))
        assertEquals(AgentRuntimeStatus.FAILED, runtime.lastCompletedTask()?.status)
        assertEquals(TaskCompletionState.FAILURE,
            working.snapshot().lastCompletedTask?.completionState)
    }

    @Test fun explicitlyRequestedOneScrollHasItsOwnOutcomeWithoutInventingUrl() {
        val accepted = BrowserNavigationTaskEvidence.afterScroll(true)
        assertEquals(GeneralVerificationStatus.SUCCESS, accepted.generalStatus)
        assertEquals(TaskCompletionState.SUCCESS, accepted.taskState)
        assertFalse(accepted.destinationVerified)
        assertFalse(accepted.permitsAutonomousContinuation)
        assertTrue(accepted.observed.contains("two_fresh_observations"))
        val unknown = BrowserNavigationTaskEvidence.afterScroll(false)
        assertEquals(GeneralVerificationStatus.UNKNOWN, unknown.generalStatus)
        assertEquals(TaskCompletionState.UNKNOWN, unknown.taskState)
        assertEquals(GeneralVerificationStatus.FAILURE,
            BrowserNavigationTaskEvidence.scrollRejected().generalStatus)
    }

}
