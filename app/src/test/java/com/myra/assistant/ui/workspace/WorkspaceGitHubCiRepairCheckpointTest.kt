package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubCiRepairCheckpointTest {
    @Test fun freshCiFailureHasNoReservedRepair() {
        val result = WorkspaceGitHubCiRepairCheckpoint.classify(
            phase = "ci_failed",
            repairAttempt = 0,
            budget = WorkspaceGitHubTaskBudget.State(),
        )
        assertFalse(result.reserved)
        assertFalse(result.legacyMigration)
    }

    @Test fun repairPreCommitResumesWithoutChargingRepairAgain() {
        val budget = WorkspaceGitHubTaskBudget.consumeCiRepair(
            WorkspaceGitHubTaskBudget.State()
        )
        val result = WorkspaceGitHubCiRepairCheckpoint.classify(
            phase = WorkspaceGitHubCiRepairCheckpoint.PHASE,
            repairAttempt = 0,
            budget = budget,
        )
        assertTrue(result.reserved)
        assertFalse(result.legacyMigration)
    }

    @Test fun legacySpentCiFailedCheckpointMigratesAsReservedRepair() {
        val budget = WorkspaceGitHubTaskBudget.consumeCiRepair(
            WorkspaceGitHubTaskBudget.State()
        )
        val result = WorkspaceGitHubCiRepairCheckpoint.classify(
            phase = "ci_failed",
            repairAttempt = 0,
            budget = budget,
        )
        assertTrue(result.reserved)
        assertTrue(result.legacyMigration)
    }

    @Test fun repairPreCommitRejectsImpossibleBudgetState() {
        assertTrue(runCatching {
            WorkspaceGitHubCiRepairCheckpoint.classify(
                phase = WorkspaceGitHubCiRepairCheckpoint.PHASE,
                repairAttempt = 0,
                budget = WorkspaceGitHubTaskBudget.State(),
            )
        }.isFailure)
    }
}
