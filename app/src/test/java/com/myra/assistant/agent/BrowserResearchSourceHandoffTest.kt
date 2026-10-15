package com.myra.assistant.agent

import org.junit.Assert.*
import org.junit.Test

class BrowserResearchSourceHandoffTest {
    private fun completedContext(
        at: Long = 1_000L,
        query: String = "android security updates",
        state: TaskCompletionState = TaskCompletionState.UNKNOWN,
        outcome: String = BrowserResearchSourceHandoff.REQUIRED_OUTCOME,
    ) = WorkingTaskContext(
        lastCompletedTask = CompletedTaskContext(
            taskId = "research-1",
            goal = "research android security updates",
            action = ToolCapability.OBSERVE_SCREEN.name,
            query = query,
            destination = SearchDestination.BROWSER,
            executor = ToolCapability.BROWSER_SEARCH.name,
            observedOutcome = outcome,
            completionState = state,
            completedAt = at,
        )
    )

    @Test fun onlyFreshUnfinishedVerifiedResearchCanOfferOneSelectedSourceHandoff() {
        val pending = requireNotNull(BrowserResearchSourceHandoff.pending(
            completedContext(), 1_500L))
        assertEquals("research-1", pending.taskId)
        assertEquals("android security updates", pending.query)

        assertNull(BrowserResearchSourceHandoff.pending(
            completedContext(state = TaskCompletionState.SUCCESS), 1_500L))
        assertNull(BrowserResearchSourceHandoff.pending(
            completedContext(outcome = "search_results_visible"), 1_500L))
        assertNull(BrowserResearchSourceHandoff.pending(
            completedContext(query = "password reset"), 1_500L))
        assertNull(BrowserResearchSourceHandoff.pending(
            completedContext(), 1_000L + BrowserResearchSourceHandoff.MAX_AGE_MS + 1L))
    }

    @Test fun existingWorkingTaskOwnerClaimsSameResearchContinuationExactlyOnce() {
        var now = 1_000L
        val store = WorkingTaskContextStore(now = { now })
        val intent = StructuredAgentIntent(
            turnIntent = TurnIntent.MULTI_STEP_GOAL,
            originalUtterance = "research android security updates",
            interpretedGoal = "research android security updates",
            requiresAction = true,
            requiredCapabilities = setOf(
                ToolCapability.BROWSER_SEARCH, ToolCapability.OBSERVE_SCREEN),
            parameters = mapOf("query" to "android security updates"),
            confidence = .95,
        )
        store.syncRuntime(GeneralRuntimeTask(
            id = "research-1", turnId = 10L, intent = intent,
            status = AgentRuntimeStatus.PLANNING, createdAt = now))
        store.beginSearch(
            "android security updates", SearchDestination.BROWSER,
            ToolCapability.BROWSER_SEARCH.name, "research source evidence")
        store.completeSearch(
            BrowserResearchSourceHandoff.REQUIRED_OUTCOME,
            TaskCompletionState.UNKNOWN)

        now = 1_500L
        val pending = requireNotNull(BrowserResearchSourceHandoff.pending(
            store.snapshot(), now))
        assertTrue(store.claimResearchSourceHandoff(pending))
        assertFalse(store.claimResearchSourceHandoff(pending))
    }
}
