package com.myra.assistant.ui.workspace

/**
 * Restart-safe classification for a single bounded CI-repair reservation.
 *
 * Older builds could persist ci_failed after consuming the 1/1 repair budget but before a repair
 * commit existed. Treat that exact state as an already-reserved repair instead of charging it again.
 */
internal object WorkspaceGitHubCiRepairCheckpoint {
    const val PHASE = "repair_pre_commit"

    data class Reservation(
        val reserved: Boolean,
        val legacyMigration: Boolean,
    )

    fun classify(
        phase: String,
        repairAttempt: Int,
        budget: WorkspaceGitHubTaskBudget.State,
    ): Reservation {
        WorkspaceGitHubTaskBudget.validate(budget)
        require(repairAttempt in 0..1) { "Repair-attempt checkpoint is invalid" }

        if (phase == PHASE) {
            require(repairAttempt == 0 &&
                budget.ciRepairs == WorkspaceGitHubTaskBudget.MAX_CI_REPAIRS) {
                "repair_pre_commit must carry one reserved CI repair and no repair commit"
            }
            return Reservation(reserved = true, legacyMigration = false)
        }

        val legacy =
            phase == "ci_failed" &&
                repairAttempt == 0 &&
                budget.ciRepairs == WorkspaceGitHubTaskBudget.MAX_CI_REPAIRS
        return Reservation(reserved = legacy, legacyMigration = legacy)
    }
}
