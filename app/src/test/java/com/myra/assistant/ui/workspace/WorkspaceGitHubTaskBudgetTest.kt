package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubTaskBudgetTest {
    @Test fun legitimateBoundedPathFitsBudget() {
        var state = WorkspaceGitHubTaskBudget.State()
        repeat(5) { state = WorkspaceGitHubTaskBudget.consumeProvider(state) }
        repeat(3) { state = WorkspaceGitHubTaskBudget.consumeReview(state) }
        repeat(2) { state = WorkspaceGitHubTaskBudget.consumeFallback(state) }
        state = WorkspaceGitHubTaskBudget.consumeCiRepair(state)
        repeat(2) { state = WorkspaceGitHubTaskBudget.consumeCommit(state) }

        assertEquals(5, state.providerCalls)
        assertEquals(3, state.reviewCalls)
        assertEquals(2, state.fallbackSwitches)
        assertEquals(1, state.ciRepairs)
        assertEquals(2, state.commitAttempts)
        assertTrue(WorkspaceGitHubTaskBudget.summary(state).contains("provider 5/5"))
    }

    @Test fun everyBudgetHardStopsAfterItsMaximum() {
        var providers = WorkspaceGitHubTaskBudget.State()
        repeat(5) { providers = WorkspaceGitHubTaskBudget.consumeProvider(providers) }
        assertTrue(runCatching { WorkspaceGitHubTaskBudget.consumeProvider(providers) }.isFailure)

        var reviews = WorkspaceGitHubTaskBudget.State()
        repeat(3) { reviews = WorkspaceGitHubTaskBudget.consumeReview(reviews) }
        assertTrue(runCatching { WorkspaceGitHubTaskBudget.consumeReview(reviews) }.isFailure)

        var fallback = WorkspaceGitHubTaskBudget.State()
        repeat(2) { fallback = WorkspaceGitHubTaskBudget.consumeFallback(fallback) }
        assertTrue(runCatching { WorkspaceGitHubTaskBudget.consumeFallback(fallback) }.isFailure)

        val repair = WorkspaceGitHubTaskBudget.consumeCiRepair(WorkspaceGitHubTaskBudget.State())
        assertTrue(runCatching { WorkspaceGitHubTaskBudget.consumeCiRepair(repair) }.isFailure)

        var commits = WorkspaceGitHubTaskBudget.State()
        repeat(2) { commits = WorkspaceGitHubTaskBudget.consumeCommit(commits) }
        assertTrue(runCatching { WorkspaceGitHubTaskBudget.consumeCommit(commits) }.isFailure)
    }

    @Test fun failureReasonIsBoundedAndRedacted() {
        val state = WorkspaceGitHubTaskBudget.withFailure(
            WorkspaceGitHubTaskBudget.State(),
            "provider failed token=secret-value " + "x".repeat(600),
        )
        assertTrue(state.lastFailure.length <= 320)
        assertTrue(!state.lastFailure.contains("secret-value"))
        assertTrue(state.lastFailure.contains("[redacted]"))
    }
}
