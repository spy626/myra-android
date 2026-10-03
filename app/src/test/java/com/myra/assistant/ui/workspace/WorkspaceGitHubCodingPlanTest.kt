package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubCodingPlanTest {
    @Test fun planIsDeterministicBoundedAndFeatureBranchOnly() {
        val plan = WorkspaceGitHubCodingPlan.create(
            goal = "  GitHub checkout state fix karo  ",
            repository = "spy626/myra-android",
            branch = "agent/myra-phase-1",
            selectedPaths = listOf("src/CheckoutState.kt", "src/CheckoutScreen.kt"),
        )
        assertEquals("GitHub checkout state fix karo", plan.goal)
        assertEquals(2, plan.selectedPaths.size)
        assertTrue(plan.expectedChange.contains("explicit user-requested behavior"))
        assertTrue(plan.completionCriteria.any { it.contains("exact committed SHA") })
        assertTrue(runCatching {
            WorkspaceGitHubCodingPlan.create(
                "fix", "spy626/myra-android", "main", listOf("src/A.kt")
            )
        }.isFailure)
    }

    @Test fun completionRequiresSelectedPathsAndExactGreenSha() {
        val plan = WorkspaceGitHubCodingPlan.create(
            "GitHub checkout fix karo",
            "spy626/myra-android",
            "agent/myra-phase-1",
            listOf("src/A.kt", "src/B.kt"),
        )
        val receipt = WorkspaceGitHubConnector.CommitReceipt(
            repository = "spy626/myra-android",
            branch = "agent/myra-phase-1",
            previousHead = "a".repeat(40),
            commitSha = "b".repeat(40),
            files = listOf("src/A.kt"),
        )
        val green = WorkspaceGitHubConnector.WorkflowRun(
            id = 42,
            runNumber = 3200,
            name = "Build Android APK",
            headSha = "b".repeat(40),
            status = "completed",
            conclusion = "success",
            url = "https://github.com/spy626/myra-android/actions/runs/42",
        )
        WorkspaceGitHubCodingPlan.verifyCompletion(plan, receipt, green)

        assertTrue(runCatching {
            WorkspaceGitHubCodingPlan.verifyCompletion(
                plan, receipt.copy(files = listOf("src/Outside.kt")), green
            )
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceGitHubCodingPlan.verifyCompletion(
                plan, receipt, green.copy(headSha = "c".repeat(40))
            )
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceGitHubCodingPlan.verifyCompletion(
                plan, receipt, green.copy(conclusion = "failure")
            )
        }.isFailure)
    }
}
