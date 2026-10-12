package com.myra.assistant.agent

/** Pizza-Bot-inspired read-only task receipts over the EXISTING GeneralAgentRuntime owner. */
data class UnifiedTaskReceipt(
    val taskId: String,
    val sourceTurnId: Long,
    val planRevision: Int,
    val status: AgentRuntimeStatus,
    val activeStepId: String?,
    val lastVerifiedStepIds: List<String>,
    val waitingForApproval: Boolean,
    val needsFreshObservation: Boolean,
    val verifiedComplete: Boolean,
    val lastFailureReason: String?,
)

object UnifiedTaskReceiptPolicy {
    fun from(task: GeneralRuntimeTask): UnifiedTaskReceipt {
        val verified = task.verifiedGoalSubsteps.map { it.stepId }.distinct().takeLast(12)
        val lastFailure = task.actionHistory.lastOrNull { it.failureReason != null }?.failureReason
        val finished = task.status == AgentRuntimeStatus.COMPLETED &&
            (verified.isNotEmpty() || task.actionHistory.any {
                it.verification == GeneralVerificationStatus.SUCCESS
            })
        return UnifiedTaskReceipt(
            taskId = task.id,
            sourceTurnId = task.turnId,
            planRevision = task.planRevision,
            status = task.status,
            activeStepId = task.currentStep?.id,
            lastVerifiedStepIds = verified,
            waitingForApproval = task.status == AgentRuntimeStatus.WAITING_FOR_USER_AUTH,
            // Never auto-resume a privileged Android action from a stale screen snapshot.
            needsFreshObservation = task.status !in setOf(
                AgentRuntimeStatus.COMPLETED, AgentRuntimeStatus.CANCELLED,
                AgentRuntimeStatus.FAILED,
            ),
            verifiedComplete = finished,
            lastFailureReason = lastFailure,
        )
    }

    /** An event/receipt is descriptive, never approval or authority to execute. */
    fun mayReportSuccess(receipt: UnifiedTaskReceipt): Boolean = receipt.verifiedComplete
}
