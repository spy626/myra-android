package com.myra.assistant.ui.workspace

import java.security.MessageDigest

/**
 * Human-readable proposal derived from one evidence-qualified improvement candidate.
 *
 * Proposal identity binds the exact candidate evidence snapshot. A proposal is never activation,
 * execution authority, a permission grant, or a skill/code mutation.
 */
internal object WorkspaceWorkflowImprovementProposal {
    private const val MAX_PROPOSALS = 3

    data class Proposal(
        val id: String,
        val candidateSignatureSha256: String,
        val evidenceSha256: String,
        val repository: String,
        val branch: String,
        val verifiedExecutions: Int,
        val userSupportedExecutions: Int,
        val evidenceRefs: List<String>,
        val recoverySignals: List<String>,
        val summary: String,
    )

    private val sha = Regex("""[0-9a-f]{64}""")
    private val id = Regex("""workflow-proposal:[0-9a-f]{64}""")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun fromCandidate(
        candidate: WorkspaceWorkflowImprovementGate.Candidate,
    ): Proposal {
        require(candidate.status ==
            WorkspaceWorkflowImprovementGate.Status.READY_FOR_MANUAL_IMPROVEMENT_PROPOSAL) {
            "Workflow proposal requires an eligible improvement candidate"
        }
        require(sha.matches(candidate.signatureSha256)) {
            "Workflow proposal candidate signature is invalid"
        }
        WorkspaceConnectorPolicy.requireRepository(candidate.repository)
        WorkspaceConnectorPolicy.requireFeatureBranch(candidate.branch)
        require(candidate.verifiedExecutions >= 2 &&
            candidate.userSupportedExecutions >= 2) {
            "Workflow proposal lacks repeated verified USER support"
        }
        require(candidate.evidenceRefs.isNotEmpty() &&
            candidate.evidenceRefs.distinct().size == candidate.evidenceRefs.size) {
            "Workflow proposal evidence refs are invalid"
        }

        val canonical = listOf(
            candidate.signatureSha256,
            candidate.repository.lowercase(),
            candidate.branch,
            candidate.verifiedExecutions.toString(),
            candidate.userSupportedExecutions.toString(),
            candidate.capabilities.sorted().joinToString(","),
            candidate.constraints.sorted().joinToString(","),
            candidate.evidenceRefs.sorted().joinToString(","),
            candidate.recoverySignals.sorted().joinToString(","),
        ).joinToString("|")
        val proposalDigest = sha256("workflow-improvement-proposal-v1|" + canonical)
        val evidenceDigest = sha256(candidate.evidenceRefs.sorted().joinToString("|"))
        val proposalId = "workflow-proposal:" + proposalDigest
        val summary = buildString {
            appendLine("Proposal ID: " + proposalId)
            appendLine(
                "Scope: reusable planning guidance for verified connected-repository workflow " +
                    "mechanics on " + candidate.repository + " · " + candidate.branch + "."
            )
            appendLine(
                "Evidence: " + candidate.verifiedExecutions +
                    " verified successful executions · " +
                    candidate.userSupportedExecutions + " grounded USER confirmations."
            )
            appendLine("Verification refs: " + candidate.evidenceRefs.joinToString(", "))
            if (candidate.recoverySignals.isNotEmpty()) {
                appendLine("Observed recovery signals: " +
                    candidate.recoverySignals.joinToString(", "))
            }
            appendLine(
                "If later activated through a separate gate, this may inform planning/continuity " +
                    "for similar workflows only."
            )
            append(
                "This proposal does NOT execute anything, edit code/skills, widen permissions, " +
                    "bypass current-turn authority, or claim current repository state."
            )
        }
        require(id.matches(proposalId) && summary.length <= 4_000) {
            "Workflow proposal projection is invalid"
        }
        return Proposal(
            id = proposalId,
            candidateSignatureSha256 = candidate.signatureSha256,
            evidenceSha256 = evidenceDigest,
            repository = candidate.repository,
            branch = candidate.branch,
            verifiedExecutions = candidate.verifiedExecutions,
            userSupportedExecutions = candidate.userSupportedExecutions,
            evidenceRefs = candidate.evidenceRefs.toList(),
            recoverySignals = candidate.recoverySignals.toList(),
            summary = summary,
        )
    }

    fun instructions(
        proposals: List<Proposal>,
        approvals: Collection<WorkspaceWorkflowImprovementApproval.Record>,
    ): String {
        if (proposals.isEmpty()) return ""
        val approvedIds = approvals
            .map(WorkspaceWorkflowImprovementApproval::validate)
            .map { it.proposalId }
            .toSet()
        return buildString {
            appendLine(
                "WORKFLOW IMPROVEMENT PROPOSALS — human-readable consent step only; " +
                    "NEVER activation/execution authority:"
            )
            proposals.take(MAX_PROPOSALS).forEach { proposal ->
                appendLine(proposal.summary)
                appendLine(
                    "Approval state: " +
                        if (proposal.id in approvedIds) "RECORDED_FOR_THIS_EXACT_PROPOSAL"
                        else "NOT_RECORDED"
                )
            }
            append(
                "- If the USER asks about one proposal, show its exact Proposal ID and summary. " +
                    "For approval, present one proposal at a time. General agreement, capability " +
                    "questions, old conversation, or a different proposal never count as approval. " +
                    "Recorded approval is consent evidence only; nothing becomes active in this step."
            )
        }
    }
}
