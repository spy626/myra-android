package com.myra.assistant.ui.workspace

import java.security.MessageDigest
import org.json.JSONObject

/**
 * User-authored feedback bound to one verified workflow execution.
 *
 * This is learning evidence only. It never authorizes execution, rewrites the verified execution,
 * changes permissions, or stores hidden reasoning/provider content.
 */
internal object WorkspaceWorkflowFeedback {
    data class Record(
        val id: String,
        val targetExperienceId: String,
        val kind: WorkspaceSkillImprovementEvidence.Kind,
        val signal: WorkspaceSkillImprovementEvidence.Signal,
        val sourceTurnRef: String,
        val feedbackText: String?,
        val capturedAtMs: Long,
    )

    private val id = Regex("""feedback:[0-9a-f]{64}""")
    private val target = Regex("""github:[0-9a-f]{40,64}""")
    private val turnRef = Regex("""turn:[0-9a-f]{32}""")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun fromUserTurn(
        targetExperienceId: String,
        decision: WorkspaceWorkflowFeedbackIntent.Decision,
        sourceTurnId: String,
        userText: String,
        capturedAtMs: Long,
    ): Record {
        val cleanText = WorkspaceWorkTrace.safeText(userText, 500)
            .takeIf { it.isNotBlank() && !WorkspaceSourceContext.containsPossibleSecret(it) }
        val source = "turn:" + sha256(sourceTurnId).take(32)
        val evidenceKind = when (decision.kind) {
            WorkspaceWorkflowFeedbackIntent.Kind.CONFIRM ->
                WorkspaceSkillImprovementEvidence.Kind.USER_CONFIRMED
            WorkspaceWorkflowFeedbackIntent.Kind.CORRECT ->
                WorkspaceSkillImprovementEvidence.Kind.USER_CORRECTED
            WorkspaceWorkflowFeedbackIntent.Kind.UNDO ->
                WorkspaceSkillImprovementEvidence.Kind.USER_UNDO
        }
        val signal = when (decision.kind) {
            WorkspaceWorkflowFeedbackIntent.Kind.CONFIRM ->
                WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT
            WorkspaceWorkflowFeedbackIntent.Kind.CORRECT,
            WorkspaceWorkflowFeedbackIntent.Kind.UNDO ->
                WorkspaceSkillImprovementEvidence.Signal.COUNTER_EVIDENCE
        }
        val digest = sha256(
            listOf(
                targetExperienceId,
                evidenceKind.name,
                signal.name,
                source,
                cleanText.orEmpty(),
            ).joinToString("|")
        )
        return validate(
            Record(
                id = "feedback:" + digest,
                targetExperienceId = targetExperienceId,
                kind = evidenceKind,
                signal = signal,
                sourceTurnRef = source,
                feedbackText = cleanText,
                capturedAtMs = capturedAtMs,
            )
        )
    }

    fun validate(record: Record): Record {
        require(id.matches(record.id)) { "Workflow feedback ID is invalid" }
        require(target.matches(record.targetExperienceId)) {
            "Workflow feedback target is invalid"
        }
        require(record.kind in setOf(
            WorkspaceSkillImprovementEvidence.Kind.USER_CONFIRMED,
            WorkspaceSkillImprovementEvidence.Kind.USER_CORRECTED,
            WorkspaceSkillImprovementEvidence.Kind.USER_UNDO,
        )) { "Workflow feedback evidence kind is invalid" }
        when (record.kind) {
            WorkspaceSkillImprovementEvidence.Kind.USER_CONFIRMED ->
                require(record.signal ==
                    WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT) {
                    "User confirmation must be supporting evidence"
                }
            WorkspaceSkillImprovementEvidence.Kind.USER_CORRECTED,
            WorkspaceSkillImprovementEvidence.Kind.USER_UNDO ->
                require(record.signal ==
                    WorkspaceSkillImprovementEvidence.Signal.COUNTER_EVIDENCE) {
                    "User correction/undo must be counter-evidence"
                }
            else -> error("Unsupported workflow feedback kind")
        }
        require(turnRef.matches(record.sourceTurnRef)) {
            "Workflow feedback source turn reference is invalid"
        }
        record.feedbackText?.let {
            require(it.isNotBlank() && it.length <= 500) {
                "Workflow feedback text is invalid"
            }
            require(!WorkspaceSourceContext.containsPossibleSecret(it)) {
                "Workflow feedback text contains possible secret"
            }
        }
        require(record.capturedAtMs >= 0L) { "Workflow feedback timestamp is invalid" }
        return record
    }

    fun toJson(record: Record): JSONObject {
        val safe = validate(record)
        return JSONObject()
            .put("id", safe.id)
            .put("targetExperienceId", safe.targetExperienceId)
            .put("kind", safe.kind.name)
            .put("signal", safe.signal.name)
            .put("sourceTurnRef", safe.sourceTurnRef)
            .put("feedbackText", safe.feedbackText ?: JSONObject.NULL)
            .put("capturedAtMs", safe.capturedAtMs)
    }

    fun fromJson(root: JSONObject): Record? = runCatching {
        validate(
            Record(
                id = root.getString("id"),
                targetExperienceId = root.getString("targetExperienceId"),
                kind = WorkspaceSkillImprovementEvidence.Kind.valueOf(root.getString("kind")),
                signal = WorkspaceSkillImprovementEvidence.Signal.valueOf(root.getString("signal")),
                sourceTurnRef = root.getString("sourceTurnRef"),
                feedbackText = if (root.isNull("feedbackText")) null
                    else root.getString("feedbackText"),
                capturedAtMs = root.getLong("capturedAtMs"),
            )
        )
    }.getOrNull()
}
