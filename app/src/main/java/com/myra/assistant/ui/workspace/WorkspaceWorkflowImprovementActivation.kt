package com.myra.assistant.ui.workspace

import java.security.MessageDigest
import org.json.JSONObject

/**
 * Separate, reversible planning-only activation of one already-approved exact proposal.
 *
 * Every projection rechecks the full retained evidence. An approval alone never activates.
 * This has NO route to WorkspaceExecutionAuthority, the connector, source edits or skills.
 */
internal object WorkspaceWorkflowImprovementActivation {
    data class Record(
        val id: String,
        val proposalId: String,
        val candidateSignatureSha256: String,
        val evidenceSha256: String,
        val approvalId: String,
        val sourceTurnRef: String,
        val activatedAtMs: Long,
    )

    // Outcome of a single user-authorized approval-plus-activation turn. The approval may
    // survive a failed post-write revalidation, but activation is never claimed without proof.
    data class ApprovalActivation(
        val approval: WorkspaceWorkflowImprovementApproval.Record,
        val activation: Record?,
    )

    private val sha = Regex("""[0-9a-f]{64}""")
    private val proposalIdPattern = Regex("""workflow-proposal:[0-9a-f]{64}""")
    private val approvalIdPattern = Regex("""workflow-approval:[0-9a-f]{64}""")
    private val idPattern = Regex("""workflow-activation:[0-9a-f]{64}""")
    private val turnRefPattern = Regex("""turn:[0-9a-f]{32}""")
    private val requestPattern = Regex(
        """(?iu)^(?:please\s+)?(?:activate|enable)\s+(?:(?:this|the)\s+)?(?:(?:workflow|improvement)\s+)?proposal(?:\s+workflow-proposal:[0-9a-f]{64})?\s*[.!]?$"""
    )
    private val directIdPattern = Regex(
        """(?iu)^(?:please\s+)?(?:activate|enable)\s+workflow-proposal:[0-9a-f]{64}\s*[.!]?$"""
    )

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun isExplicitRequest(raw: String): Boolean {
        val value = raw.trim().replace(Regex("""[\s\p{Z}]+"""), " ")
        if (value.length !in 1..300 || WorkspaceSourceContext.containsPossibleSecret(value)) {
            return false
        }
        return requestPattern.matches(value) || directIdPattern.matches(value)
    }

    fun currentProposals(
        experiences: Collection<WorkspaceWorkflowExperience.Record>,
        feedback: Collection<WorkspaceWorkflowFeedback.Record>,
    ): List<WorkspaceWorkflowImprovementProposal.Proposal> =
        WorkspaceWorkflowImprovementGate.evaluate(
            WorkspaceWorkflowReflection.reflect(experiences, feedback)
        ).map(WorkspaceWorkflowImprovementProposal::fromCandidate)

    private fun matchingCurrentProposal(
        requestedId: String,
        experiences: Collection<WorkspaceWorkflowExperience.Record>,
        feedback: Collection<WorkspaceWorkflowFeedback.Record>,
    ): WorkspaceWorkflowImprovementProposal.Proposal? {
        val reflections = WorkspaceWorkflowReflection.reflect(experiences, feedback)
        val candidate = WorkspaceWorkflowImprovementGate.evaluate(reflections)
            .firstOrNull {
                WorkspaceWorkflowImprovementProposal.fromCandidate(it).id == requestedId
            } ?: return null
        // Even an older correction followed by a confirmation blocks this activation.
        // Reflection disposition alone retains only the latest feedback per execution.
        val familyIds = reflections.filter {
            it.kind == candidate.kind &&
                it.repository.equals(candidate.repository, ignoreCase = true) &&
                it.branch == candidate.branch &&
                it.capabilities.sorted() == candidate.capabilities.sorted() &&
                it.constraints.sorted() == candidate.constraints.sorted()
        }.map { it.experienceId }.toSet()
        if (feedback.map(WorkspaceWorkflowFeedback::validate).any {
                it.targetExperienceId in familyIds &&
                    it.signal == WorkspaceSkillImprovementEvidence.Signal.COUNTER_EVIDENCE
            }) return null
        return WorkspaceWorkflowImprovementProposal.fromCandidate(candidate)
    }

    fun issue(
        requestedProposalId: String,
        sourceTurnId: String,
        activatedAtMs: Long,
        experiences: Collection<WorkspaceWorkflowExperience.Record>,
        feedback: Collection<WorkspaceWorkflowFeedback.Record>,
        approvals: Collection<WorkspaceWorkflowImprovementApproval.Record>,
    ): Record? {
        if (!proposalIdPattern.matches(requestedProposalId) ||
            sourceTurnId.isBlank() || activatedAtMs < 0L) return null
        val proposal = matchingCurrentProposal(requestedProposalId, experiences, feedback)
            ?: return null
        val approval = approvals.map(WorkspaceWorkflowImprovementApproval::validate)
            .filter {
                it.proposalId == proposal.id &&
                    it.candidateSignatureSha256 == proposal.candidateSignatureSha256 &&
                    it.evidenceSha256 == proposal.evidenceSha256
            }.sortedWith(
                compareBy<WorkspaceWorkflowImprovementApproval.Record> { it.approvedAtMs }
                    .thenBy { it.id }
            ).lastOrNull() ?: return null
        val identity = digest(proposal.id + "|" + approval.id)
        return validate(
            Record(
                id = "workflow-activation:" + identity,
                proposalId = proposal.id,
                candidateSignatureSha256 = proposal.candidateSignatureSha256,
                evidenceSha256 = proposal.evidenceSha256,
                approvalId = approval.id,
                sourceTurnRef = "turn:" + digest(sourceTurnId).take(32),
                activatedAtMs = activatedAtMs,
            )
        )
    }

    fun effective(
        record: Record,
        experiences: Collection<WorkspaceWorkflowExperience.Record>,
        feedback: Collection<WorkspaceWorkflowFeedback.Record>,
        approvals: Collection<WorkspaceWorkflowImprovementApproval.Record>,
    ): Boolean {
        val safe = validate(record)
        val current = issue(
            requestedProposalId = safe.proposalId,
            sourceTurnId = "revalidation-only",
            activatedAtMs = safe.activatedAtMs,
            experiences = experiences,
            feedback = feedback,
            approvals = approvals,
        ) ?: return false
        return safe.id == current.id &&
            safe.candidateSignatureSha256 == current.candidateSignatureSha256 &&
            safe.evidenceSha256 == current.evidenceSha256 &&
            safe.approvalId == current.approvalId
    }

    fun validate(record: Record): Record {
        require(idPattern.matches(record.id) && proposalIdPattern.matches(record.proposalId))
        require(sha.matches(record.candidateSignatureSha256) &&
            sha.matches(record.evidenceSha256))
        require(approvalIdPattern.matches(record.approvalId) &&
            turnRefPattern.matches(record.sourceTurnRef))
        require(record.id == "workflow-activation:" +
            digest(record.proposalId + "|" + record.approvalId))
        require(record.activatedAtMs >= 0L)
        return record
    }

    fun toJson(record: Record): JSONObject {
        val safe = validate(record)
        return JSONObject()
            .put("id", safe.id)
            .put("proposalId", safe.proposalId)
            .put("candidateSignatureSha256", safe.candidateSignatureSha256)
            .put("evidenceSha256", safe.evidenceSha256)
            .put("approvalId", safe.approvalId)
            .put("sourceTurnRef", safe.sourceTurnRef)
            .put("activatedAtMs", safe.activatedAtMs)
    }

    fun fromJson(value: JSONObject): Record? = runCatching {
        validate(Record(
            id = value.getString("id"),
            proposalId = value.getString("proposalId"),
            candidateSignatureSha256 = value.getString("candidateSignatureSha256"),
            evidenceSha256 = value.getString("evidenceSha256"),
            approvalId = value.getString("approvalId"),
            sourceTurnRef = value.getString("sourceTurnRef"),
            activatedAtMs = value.getLong("activatedAtMs"),
        ))
    }.getOrNull()

    fun instructions(
        records: Collection<Record>,
        experiences: Collection<WorkspaceWorkflowExperience.Record>,
        feedback: Collection<WorkspaceWorkflowFeedback.Record>,
        approvals: Collection<WorkspaceWorkflowImprovementApproval.Record>,
    ): String {
        val active = records.map(::validate).filter {
            effective(it, experiences, feedback, approvals)
        }.take(3)
        if (active.isEmpty()) return ""
        return buildString {
            appendLine("ACTIVATED WORKFLOW PLANNING GUIDANCE — REVALIDATED, ADVISORY ONLY:")
            active.forEach {
                appendLine("- Exact proposal: " + it.proposalId + "; state=ACTIVE_PLANNING_ONLY.")
            }
            appendLine("- For a similar connected-repository task, plan around the existing guarded workflow: fresh branch/head read, bounded changes, exact push-CI verification and truthful user-facing provenance.")
            append("- This is NOT a tool decision, execution permission, source/skill mutation, proof of live GitHub state, or autonomous action. Exact current USER turn and WorkspaceExecutionAuthority remain mandatory for every write. Any grounded correction/undo in the workflow family suppresses this guidance.")
        }
    }

    fun receipt(record: Record): String =
        "Improvement planning activation recorded.\nProposal ID: " + record.proposalId +
            "\nState: ACTIVE_PLANNING_ONLY (subject to evidence revalidation)." +
            "\nNo GitHub action, source/skill edit or permission change occurred. " +
            "Every write still requires exact current-turn authorization; later correction/undo disables this guidance."
}
