package com.myra.assistant.ui.workspace

import org.json.JSONObject

/**
 * Deterministic reflection over verified workflow evidence and grounded user feedback.
 *
 * This is a structured outcome summary, not chain-of-thought. It never grants execution authority,
 * edits code, changes permissions, or promotes a skill/workflow by itself.
 */
internal object WorkspaceWorkflowReflection {
    private const val MAX_REFLECTIONS = 5

    enum class FeedbackState { NONE, CONFIRMED, CORRECTED, UNDONE }
    enum class Disposition { OBSERVE_ONLY, USER_SUPPORTED, BLOCKED_BY_COUNTER_EVIDENCE }

    data class Record(
        val experienceId: String,
        val intent: String?,
        val kind: WorkspaceWorkflowExperience.Kind,
        val repository: String,
        val branch: String,
        val capabilities: List<String>,
        val constraints: List<String>,
        val verificationRef: String,
        val outcome: WorkspaceWorkflowExperience.Outcome,
        val providerCalls: Int,
        val reviewCalls: Int,
        val fallbackSwitches: Int,
        val ciRepairs: Int,
        val commitAttempts: Int,
        val recoverySignals: List<String>,
        val feedbackState: FeedbackState,
        val feedbackRef: String?,
        val disposition: Disposition,
        val reflectedAtMs: Long,
    )

    private val constraints = listOf(
        "CURRENT_TURN_AUTHORITY_REQUIRED",
        "FEATURE_BRANCH_ONLY",
        "MAIN_MASTER_FORBIDDEN",
        "EXACT_CI_GREEN_REQUIRED",
        "CI_IS_NOT_PHONE_PASS",
    )

    fun reflect(
        experiences: Collection<WorkspaceWorkflowExperience.Record>,
        feedback: Collection<WorkspaceWorkflowFeedback.Record>,
    ): List<Record> {
        val safeExperiences = experiences.map(WorkspaceWorkflowExperience::validate)
        require(safeExperiences.map { it.id }.distinct().size == safeExperiences.size) {
            "Workflow reflection input contains duplicate experiences"
        }
        val safeFeedback = feedback.map(WorkspaceWorkflowFeedback::validate)
        require(safeFeedback.map { it.id }.distinct().size == safeFeedback.size) {
            "Workflow reflection input contains duplicate feedback"
        }
        val experienceIds = safeExperiences.map { it.id }.toSet()
        val latestFeedback = safeFeedback
            .filter { it.targetExperienceId in experienceIds }
            .groupBy { it.targetExperienceId }
            .mapValues { (_, values) ->
                values.maxWithOrNull(
                    compareBy<WorkspaceWorkflowFeedback.Record> { it.capturedAtMs }
                        .thenBy { it.id }
                )!!
            }

        return safeExperiences.map { experience ->
            val latest = latestFeedback[experience.id]
            val feedbackState = when (latest?.kind) {
                WorkspaceSkillImprovementEvidence.Kind.USER_CONFIRMED -> FeedbackState.CONFIRMED
                WorkspaceSkillImprovementEvidence.Kind.USER_CORRECTED -> FeedbackState.CORRECTED
                WorkspaceSkillImprovementEvidence.Kind.USER_UNDO -> FeedbackState.UNDONE
                else -> FeedbackState.NONE
            }
            val disposition = when {
                latest?.signal == WorkspaceSkillImprovementEvidence.Signal.COUNTER_EVIDENCE ->
                    Disposition.BLOCKED_BY_COUNTER_EVIDENCE
                latest?.kind == WorkspaceSkillImprovementEvidence.Kind.USER_CONFIRMED ->
                    Disposition.USER_SUPPORTED
                else -> Disposition.OBSERVE_ONLY
            }
            val recoverySignals = buildList {
                if (experience.fallbackSwitches > 0) add("PROVIDER_FALLBACK_USED")
                if (experience.ciRepairs > 0) add("CI_REPAIR_USED")
                if (experience.commitAttempts > 1) add("MULTIPLE_COMMIT_ATTEMPTS")
            }
            Record(
                experienceId = experience.id,
                intent = experience.userTask,
                kind = experience.kind,
                repository = experience.repository,
                branch = experience.branch,
                capabilities = experience.capabilities.sorted(),
                constraints = constraints,
                verificationRef = experience.verificationRef,
                outcome = experience.outcome,
                providerCalls = experience.providerCalls,
                reviewCalls = experience.reviewCalls,
                fallbackSwitches = experience.fallbackSwitches,
                ciRepairs = experience.ciRepairs,
                commitAttempts = experience.commitAttempts,
                recoverySignals = recoverySignals,
                feedbackState = feedbackState,
                feedbackRef = latest?.id,
                disposition = disposition,
                reflectedAtMs = maxOf(experience.capturedAtMs, latest?.capturedAtMs ?: 0L),
            )
        }.sortedWith(
            compareByDescending<Record> { it.reflectedAtMs }
                .thenBy { it.experienceId }
        ).take(MAX_REFLECTIONS)
    }

    fun instructions(reflections: List<Record>): String {
        if (reflections.isEmpty()) return ""
        return buildString {
            appendLine("STRUCTURED WORKFLOW REFLECTIONS — evidence summary only; never hidden reasoning or action authority:")
            reflections.take(MAX_REFLECTIONS).forEach { reflection ->
                appendLine(
                    "- " + reflection.experienceId + ": outcome=" + reflection.outcome.name +
                        "; verification=" + reflection.verificationRef +
                        "; disposition=" + reflection.disposition.name + "."
                )
                reflection.intent?.let {
                    appendLine("- USER-authored intent: " + JSONObject.quote(it))
                }
                appendLine("- Capabilities used: " + reflection.capabilities.joinToString(", "))
                appendLine("- Constraints preserved: " + reflection.constraints.joinToString(", "))
                appendLine(
                    "- Execution counts: provider=" + reflection.providerCalls +
                        ", review=" + reflection.reviewCalls +
                        ", fallback=" + reflection.fallbackSwitches +
                        ", ciRepair=" + reflection.ciRepairs +
                        ", commit=" + reflection.commitAttempts + "."
                )
                if (reflection.recoverySignals.isNotEmpty()) {
                    appendLine("- Recovery signals: " + reflection.recoverySignals.joinToString(", "))
                }
                appendLine("- Latest grounded USER feedback: " + reflection.feedbackState.name + ".")
            }
            append(
                "- Use reflections only to understand verified outcomes and candidate improvement evidence. " +
                    "Do not expose or invent chain-of-thought, do not widen permissions, do not auto-edit, " +
                    "and do not promote a workflow without a separate improvement gate."
            )
        }
    }
}
