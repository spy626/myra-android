package com.myra.assistant.ui.workspace

import java.security.MessageDigest
import org.json.JSONObject

/**
 * Durable explicit USER approval for one exact workflow-improvement proposal snapshot.
 *
 * Approval records consent only. It never activates a workflow, edits code/skills, changes
 * permissions, or replaces the exact current-turn authority required by later execution.
 */
internal object WorkspaceWorkflowImprovementApproval {
    data class Record(
        val id: String,
        val proposalId: String,
        val candidateSignatureSha256: String,
        val evidenceSha256: String,
        val sourceTurnRef: String,
        val approvedAtMs: Long,
    )

    private val approvalId = Regex("""workflow-approval:[0-9a-f]{64}""")
    private val proposalId = Regex("""workflow-proposal:[0-9a-f]{64}""")
    private val sha = Regex("""[0-9a-f]{64}""")
    private val turnRef = Regex("""turn:[0-9a-f]{32}""")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun fromUserTurn(
        proposal: WorkspaceWorkflowImprovementProposal.Proposal,
        sourceTurnId: String,
        approvedAtMs: Long,
    ): Record {
        require(sourceTurnId.isNotBlank()) { "Workflow approval source turn is missing" }
        val source = "turn:" + sha256(sourceTurnId).take(32)
        val digest = sha256(
            listOf(
                proposal.id,
                proposal.candidateSignatureSha256,
                proposal.evidenceSha256,
                source,
            ).joinToString("|")
        )
        return validate(
            Record(
                id = "workflow-approval:" + digest,
                proposalId = proposal.id,
                candidateSignatureSha256 = proposal.candidateSignatureSha256,
                evidenceSha256 = proposal.evidenceSha256,
                sourceTurnRef = source,
                approvedAtMs = approvedAtMs,
            )
        )
    }

    fun validate(record: Record): Record {
        require(approvalId.matches(record.id)) { "Workflow approval ID is invalid" }
        require(proposalId.matches(record.proposalId)) { "Workflow approval proposal ID is invalid" }
        require(sha.matches(record.candidateSignatureSha256)) {
            "Workflow approval candidate signature is invalid"
        }
        require(sha.matches(record.evidenceSha256)) {
            "Workflow approval evidence signature is invalid"
        }
        require(turnRef.matches(record.sourceTurnRef)) {
            "Workflow approval source turn reference is invalid"
        }
        require(record.approvedAtMs >= 0L) { "Workflow approval timestamp is invalid" }
        return record
    }

    fun toJson(record: Record): JSONObject {
        val safe = validate(record)
        return JSONObject()
            .put("id", safe.id)
            .put("proposalId", safe.proposalId)
            .put("candidateSignatureSha256", safe.candidateSignatureSha256)
            .put("evidenceSha256", safe.evidenceSha256)
            .put("sourceTurnRef", safe.sourceTurnRef)
            .put("approvedAtMs", safe.approvedAtMs)
    }

    fun fromJson(root: JSONObject): Record? = runCatching {
        validate(
            Record(
                id = root.getString("id"),
                proposalId = root.getString("proposalId"),
                candidateSignatureSha256 = root.getString("candidateSignatureSha256"),
                evidenceSha256 = root.getString("evidenceSha256"),
                sourceTurnRef = root.getString("sourceTurnRef"),
                approvedAtMs = root.getLong("approvedAtMs"),
            )
        )
    }.getOrNull()

    fun receipt(
        proposal: WorkspaceWorkflowImprovementProposal.Proposal,
        approval: Record,
    ): String {
        val safe = validate(approval)
        require(safe.proposalId == proposal.id &&
            safe.candidateSignatureSha256 == proposal.candidateSignatureSha256 &&
            safe.evidenceSha256 == proposal.evidenceSha256) {
            "Workflow approval no longer matches the exact proposal"
        }
        return buildString {
            appendLine("Improvement proposal approval recorded.")
            appendLine("Proposal ID: " + proposal.id)
            appendLine("State: APPROVAL_RECORDED_ONLY.")
            appendLine(
                "Nothing was activated, no code/skill was changed, and no permission was widened."
            )
            append(
                "A later activation step must revalidate the current candidate, evidence and " +
                    "counter-evidence; current-turn authority is still required."
            )
        }
    }
}
