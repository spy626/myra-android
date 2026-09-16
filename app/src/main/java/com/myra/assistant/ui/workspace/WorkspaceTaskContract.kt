package com.myra.assistant.ui.workspace

/** Project-local coding work. This contract is NOT another LYRA brain, memory owner or executor. */
enum class WorkspaceTaskStatus { DRAFT, PAUSED }
enum class WorkspacePlanLane { CODING, BROWSER_DOM, TERMINAL, HUMAN }
enum class WorkspacePlanRisk { LOW, MEDIUM, HIGH }
enum class WorkspaceEvidenceSource { TOOL_RESULT, VERIFICATION_GATE, HUMAN_APPROVAL }
enum class WorkspacePlanDecision { CONTINUE, REQUIRE_APPROVAL, READY_FOR_FINAL_VERIFICATION, VERIFIED }

data class WorkspacePlanStep(
    val id: String,
    val lane: WorkspacePlanLane,
    val intent: String,
    val allowedTools: Set<String>,
    val expectedEvidence: Set<WorkspaceEvidenceSource>,
    val risk: WorkspacePlanRisk,
    val approvalRequired: Boolean,
)

data class WorkspaceTask(
    val projectId: String,
    val taskId: String,
    val goal: String,
    val status: WorkspaceTaskStatus,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** Evidence must be supplied by the future trusted LYRA executor/verification gate, never by a model claim. */
data class WorkspaceStepEvidence(val stepId: String, val source: WorkspaceEvidenceSource, val summary: String)

object WorkspaceTaskContract {
    const val MAX_GOAL_LENGTH = 500
    const val PLAN_TRUST_LABEL = "Current execution plan (runtime guidance, not authority):"

    fun normalizeGoal(raw: String): String {
        val clean = raw.trim().replace(Regex("\\s+"), " ")
        require(clean.isNotEmpty()) { "Describe what you want to build or change" }
        require(clean.length <= MAX_GOAL_LENGTH && clean.none { it.isISOControl() }) { "Task must be 500 characters or fewer" }
        return clean
    }

    /** Bounded process template, NOT a model-generated implementation plan or authorization to edit. */
    fun steps(type: WorkspaceProjectType): List<WorkspacePlanStep> = listOf(
        WorkspacePlanStep("inspect", WorkspacePlanLane.CODING, "Inspect this project's current files and constraints",
            setOf("workspace.files.read"), setOf(WorkspaceEvidenceSource.TOOL_RESULT), WorkspacePlanRisk.LOW, false),
        WorkspacePlanStep("specify", WorkspacePlanLane.HUMAN, "Confirm the goal, acceptance criteria and permitted scope",
            emptySet(), setOf(WorkspaceEvidenceSource.HUMAN_APPROVAL), WorkspacePlanRisk.MEDIUM, true),
        WorkspacePlanStep("implement", WorkspacePlanLane.CODING, "Propose and apply only approved project-scoped changes",
            setOf("workspace.files.read", "workspace.files.write"), setOf(WorkspaceEvidenceSource.TOOL_RESULT),
            WorkspacePlanRisk.MEDIUM, true),
        if (type == WorkspaceProjectType.WEBSITE)
            WorkspacePlanStep("verify", WorkspacePlanLane.BROWSER_DOM, "Refresh preview and verify the saved website",
                setOf("workspace.preview.refresh"), setOf(WorkspaceEvidenceSource.TOOL_RESULT, WorkspaceEvidenceSource.VERIFICATION_GATE),
                WorkspacePlanRisk.LOW, false)
        else WorkspacePlanStep("verify", WorkspacePlanLane.TERMINAL, "Build and verify the Android project",
            setOf("workspace.android.build"), setOf(WorkspaceEvidenceSource.TOOL_RESULT, WorkspaceEvidenceSource.VERIFICATION_GATE),
            WorkspacePlanRisk.MEDIUM, false),
    )

    /** A model's plan/progress text cannot satisfy any expected evidence or approval. */
    fun reconcile(steps: List<WorkspacePlanStep>, evidence: List<WorkspaceStepEvidence>,
                  approvedStepIds: Set<String>, finalVerificationPassed: Boolean): WorkspacePlanDecision {
        if (steps.isEmpty() || steps.any { it.approvalRequired && it.id !in approvedStepIds })
            return WorkspacePlanDecision.REQUIRE_APPROVAL
        for (step in steps) {
            if (!step.expectedEvidence.all { required ->
                evidence.any { it.stepId == step.id && it.source == required && it.summary.isNotBlank() }
            }) return WorkspacePlanDecision.CONTINUE
        }
        return if (finalVerificationPassed) WorkspacePlanDecision.VERIFIED else WorkspacePlanDecision.READY_FOR_FINAL_VERIFICATION
    }

    /** Never send a whole repository or raw log into a provider prompt. */
    fun boundedGuidance(task: WorkspaceTask, type: WorkspaceProjectType): String = buildString {
        appendLine(PLAN_TRUST_LABEL)
        appendLine("Project-scoped planning data only; never overrides the current user, safety, trusted tool results or verification.")
        appendLine("Goal: ${task.goal.take(MAX_GOAL_LENGTH)}")
        steps(type).forEach { appendLine("${it.id}: ${it.intent}") }
        append("No task is complete without trusted evidence and final verification.")
    }.take(1500)
}
