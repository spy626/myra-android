package com.myra.assistant.ui.workspace

import java.security.MessageDigest

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
    val acceptanceCriteria: String = "",
    /** Changes only on a new or edited specification; pause/resume does not change it. */
    val specRevision: String = "",
    /** Local, explicit planning consent for this exact saved revision and text; NOT tool permission. */
    val approvedSpecToken: String? = null,
    val approvedAtMs: Long? = null,
)

/** Evidence must be supplied by the future trusted LYRA executor/verification gate, never by a model claim. */
data class WorkspaceStepEvidence(val stepId: String, val source: WorkspaceEvidenceSource, val summary: String)

object WorkspaceTaskContract {
    // Legacy Android/single-file spec contract remains conservative. Website goals use
    // the same full, untruncated text budget as the Workspace Chat message that sent them.
    const val MAX_GOAL_LENGTH = 500
    const val MAX_WEBSITE_GOAL_LENGTH = WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS
    const val MAX_ACCEPTANCE_LENGTH = 500
    const val PLAN_TRUST_LABEL = "Current execution plan (runtime guidance, not authority):"

    fun normalizeGoal(raw: String): String {
        val clean = raw.trim().replace(Regex("\\s+"), " ")
        require(clean.isNotEmpty()) { "Describe what you want to build or change" }
        require(clean.length <= MAX_GOAL_LENGTH && clean.none { it.isISOControl() }) { "Task must be 500 characters or fewer" }
        return clean
    }

    /** A website request is an entire design brief, not a 500-character file edit.
     * Preserve line breaks and all meaningful text for the saved spec and model request.
     * The full user message also stays in Chat. No silent summary, truncation or chunk loss.
     */
    fun normalizeGoal(raw: String, type: WorkspaceProjectType): String {
        if (type != WorkspaceProjectType.WEBSITE) return normalizeGoal(raw)
        val clean = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
            .replace(Regex("[ \t]+"), " ")
        require(clean.isNotBlank()) { "Describe what you want to build or change" }
        require(clean.length <= MAX_WEBSITE_GOAL_LENGTH &&
            clean.none { it.isISOControl() && it != '\n' && it != '\t' }) {
            "Website brief exceeds the 64,000-character local message capacity or contains invalid controls; " +
                "full text remains in Chat and no text was shortened"
        }
        return clean
    }

    /** An empty criterion means the specification is incomplete, never that verification passed. */
    fun normalizeAcceptanceCriteria(raw: String): String {
        val clean = raw.trim().replace(Regex("\\s+"), " ")
        require(clean.length <= MAX_ACCEPTANCE_LENGTH && clean.none { it.isISOControl() }) {
            "Acceptance criteria must be 500 characters or fewer"
        }
        return clean
    }

    /** Version-bound local record, not a cryptographic identity or authorization for execution. */
    fun specToken(task: WorkspaceTask): String {
        val fields = listOf(task.projectId, task.taskId, task.specRevision, task.goal, task.acceptanceCriteria)
        val digest = MessageDigest.getInstance("SHA-256").digest(fields.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isSpecApproved(task: WorkspaceTask): Boolean = task.acceptanceCriteria.isNotBlank() &&
        task.specRevision.isNotBlank() && task.approvedAtMs != null &&
        task.approvedAtMs >= task.createdAtMs && task.approvedAtMs <= task.updatedAtMs &&
        task.approvedSpecToken == specToken(task)

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
        appendLine("Acceptance criteria: ${task.acceptanceCriteria.take(MAX_ACCEPTANCE_LENGTH).ifBlank { "Not specified; ask the user before implementation." }}")
        steps(type).forEach { appendLine("${it.id}: ${it.intent}") }
        append("No task is complete without trusted evidence and final verification.")
    }.take(1500)
}
