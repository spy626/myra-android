package com.myra.assistant.ui.workspace

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Bounded private sidecar for verified workflow experience evidence.
 *
 * This is not a second memory brain/database. It is append/idempotent execution evidence only;
 * future behavior changes still require separate learning/promotion gates.
 */
internal class WorkspaceWorkflowExperienceStore(
    private val root: File,
) {
    companion object {
        private const val SCHEMA = 1
        private const val FILE = "verified-workflows.json"
        private const val FEEDBACK_FILE = "verified-workflow-feedback.json"
        private const val APPROVAL_FILE = "workflow-improvement-approvals.json"
        private const val ACTIVATION_FILE = "workflow-improvement-activations.json"
        private const val MAX_RECORDS = 64
        private const val MAX_FEEDBACK_RECORDS = 128
        private const val MAX_APPROVAL_RECORDS = 64
        private const val MAX_ACTIVATION_RECORDS = 64
        private const val MAX_BYTES = 256 * 1024L
    }

    private fun base(): File {
        require(root.mkdirs() || root.isDirectory) { "Workflow experience root is unavailable" }
        require(!Files.isSymbolicLink(root.toPath())) {
            "Workflow experience root link is forbidden"
        }
        return root.canonicalFile
    }

    private fun file(): File = storageFile(FILE, "Workflow experience")

    private fun feedbackFile(): File =
        storageFile(FEEDBACK_FILE, "Workflow feedback")

    private fun approvalFile(): File =
        storageFile(APPROVAL_FILE, "Workflow improvement approval")

    private fun activationFile(): File =
        storageFile(ACTIVATION_FILE, "Workflow improvement activation")

    private fun storageFile(name: String, label: String): File {
        val parent = base()
        val value = File(parent, name)
        require(!Files.isSymbolicLink(value.toPath())) {
            "$label file link is forbidden"
        }
        require(value.canonicalFile.parentFile == parent) {
            "$label path escaped storage"
        }
        return value.canonicalFile
    }

    private fun decode(raw: String): List<WorkspaceWorkflowExperience.Record> {
        require(raw.toByteArray().size <= MAX_BYTES) {
            "Workflow experience store is too large"
        }
        val rootJson = JSONObject(raw)
        require(rootJson.getInt("schema") == SCHEMA) {
            "Unsupported workflow experience schema"
        }
        val array = rootJson.getJSONArray("records")
        require(array.length() <= MAX_RECORDS) {
            "Workflow experience record count is invalid"
        }
        val records = buildList {
            for (i in 0 until array.length()) {
                add(requireNotNull(
                    WorkspaceWorkflowExperience.fromJson(array.getJSONObject(i))
                ) { "Workflow experience record is invalid" })
            }
        }
        require(records.map { it.id }.distinct().size == records.size) {
            "Workflow experience IDs must be unique"
        }
        return records
    }

    private fun encode(records: List<WorkspaceWorkflowExperience.Record>): String {
        require(records.size <= MAX_RECORDS) { "Workflow experience store exceeds its bound" }
        require(records.map { it.id }.distinct().size == records.size) {
            "Workflow experience IDs must be unique"
        }
        val text = JSONObject()
            .put("schema", SCHEMA)
            .put("records", JSONArray(records.map(WorkspaceWorkflowExperience::toJson)))
            .toString()
        require(text.toByteArray().size <= MAX_BYTES) {
            "Workflow experience store exceeds its byte bound"
        }
        return text
    }

    private fun atomicWrite(target: File, text: String) {
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        require(temp.canonicalFile.parentFile == target.parentFile) {
            "Workflow experience temp path escaped storage"
        }
        try {
            java.io.FileOutputStream(temp).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    @Synchronized fun list(): List<WorkspaceWorkflowExperience.Record> {
        val target = file()
        if (!target.exists()) return emptyList()
        require(target.isFile && target.length() in 1..MAX_BYTES) {
            "Workflow experience store is unavailable or too large"
        }
        return decode(target.readText())
    }

    @Synchronized fun record(
        record: WorkspaceWorkflowExperience.Record,
    ): WorkspaceWorkflowExperience.Record {
        val safe = WorkspaceWorkflowExperience.validate(record)
        val current = list().filterNot { it.id == safe.id }
        val next = (current + safe)
            .sortedWith(compareBy<WorkspaceWorkflowExperience.Record> { it.capturedAtMs }
                .thenBy { it.id })
            .takeLast(MAX_RECORDS)
        atomicWrite(file(), encode(next))
        val reopened = list().firstOrNull { it.id == safe.id }
            ?: throw IllegalStateException("Workflow experience could not be verified after write")
        require(reopened == safe) { "Workflow experience changed after persistence" }
        pruneInvalidPlanningActivations()
        return reopened
    }

    private fun decodeFeedback(raw: String): List<WorkspaceWorkflowFeedback.Record> {
        require(raw.toByteArray().size <= MAX_BYTES) {
            "Workflow feedback store is too large"
        }
        val rootJson = JSONObject(raw)
        require(rootJson.getInt("schema") == SCHEMA) {
            "Unsupported workflow feedback schema"
        }
        val array = rootJson.getJSONArray("records")
        require(array.length() <= MAX_FEEDBACK_RECORDS) {
            "Workflow feedback record count is invalid"
        }
        val records = buildList {
            for (i in 0 until array.length()) {
                add(requireNotNull(
                    WorkspaceWorkflowFeedback.fromJson(array.getJSONObject(i))
                ) { "Workflow feedback record is invalid" })
            }
        }
        require(records.map { it.id }.distinct().size == records.size) {
            "Workflow feedback IDs must be unique"
        }
        return records
    }

    private fun encodeFeedback(records: List<WorkspaceWorkflowFeedback.Record>): String {
        require(records.size <= MAX_FEEDBACK_RECORDS) {
            "Workflow feedback store exceeds its bound"
        }
        require(records.map { it.id }.distinct().size == records.size) {
            "Workflow feedback IDs must be unique"
        }
        val text = JSONObject()
            .put("schema", SCHEMA)
            .put("records", JSONArray(records.map(WorkspaceWorkflowFeedback::toJson)))
            .toString()
        require(text.toByteArray().size <= MAX_BYTES) {
            "Workflow feedback store exceeds its byte bound"
        }
        return text
    }

    @Synchronized fun listFeedback(): List<WorkspaceWorkflowFeedback.Record> {
        val target = feedbackFile()
        if (!target.exists()) return emptyList()
        require(target.isFile && target.length() in 1..MAX_BYTES) {
            "Workflow feedback store is unavailable or too large"
        }
        return decodeFeedback(target.readText())
    }

    @Synchronized fun recordFeedback(
        record: WorkspaceWorkflowFeedback.Record,
    ): WorkspaceWorkflowFeedback.Record {
        val safe = WorkspaceWorkflowFeedback.validate(record)
        require(list().any { it.id == safe.targetExperienceId }) {
            "Workflow feedback target is not a retained verified experience"
        }
        val current = listFeedback().filterNot { it.id == safe.id }
        val next = (current + safe)
            .sortedWith(compareBy<WorkspaceWorkflowFeedback.Record> { it.capturedAtMs }
                .thenBy { it.id })
            .takeLast(MAX_FEEDBACK_RECORDS)
        atomicWrite(feedbackFile(), encodeFeedback(next))
        val reopened = listFeedback().firstOrNull { it.id == safe.id }
            ?: throw IllegalStateException("Workflow feedback could not be verified after write")
        require(reopened == safe) { "Workflow feedback changed after persistence" }
        // Revoke stale/contradicted planning guidance durably, not merely in one prompt.
        pruneInvalidPlanningActivations()
        return reopened
    }

    private fun decodeApprovals(
        raw: String,
    ): List<WorkspaceWorkflowImprovementApproval.Record> {
        require(raw.toByteArray().size <= MAX_BYTES) {
            "Workflow approval store is too large"
        }
        val rootJson = JSONObject(raw)
        require(rootJson.getInt("schema") == SCHEMA) {
            "Unsupported workflow approval schema"
        }
        val array = rootJson.getJSONArray("records")
        require(array.length() <= MAX_APPROVAL_RECORDS) {
            "Workflow approval record count is invalid"
        }
        val records = buildList {
            for (i in 0 until array.length()) {
                add(requireNotNull(
                    WorkspaceWorkflowImprovementApproval.fromJson(array.getJSONObject(i))
                ) { "Workflow approval record is invalid" })
            }
        }
        require(records.map { it.id }.distinct().size == records.size) {
            "Workflow approval IDs must be unique"
        }
        return records
    }

    private fun encodeApprovals(
        records: List<WorkspaceWorkflowImprovementApproval.Record>,
    ): String {
        require(records.size <= MAX_APPROVAL_RECORDS) {
            "Workflow approval store exceeds its bound"
        }
        require(records.map { it.id }.distinct().size == records.size) {
            "Workflow approval IDs must be unique"
        }
        val text = JSONObject()
            .put("schema", SCHEMA)
            .put("records", JSONArray(records.map(
                WorkspaceWorkflowImprovementApproval::toJson)))
            .toString()
        require(text.toByteArray().size <= MAX_BYTES) {
            "Workflow approval store exceeds its byte bound"
        }
        return text
    }

    @Synchronized fun listApprovals():
        List<WorkspaceWorkflowImprovementApproval.Record> {
        val target = approvalFile()
        if (!target.exists()) return emptyList()
        require(target.isFile && target.length() in 1..MAX_BYTES) {
            "Workflow approval store is unavailable or too large"
        }
        return decodeApprovals(target.readText())
    }

    @Synchronized fun recordApproval(
        record: WorkspaceWorkflowImprovementApproval.Record,
    ): WorkspaceWorkflowImprovementApproval.Record {
        val safe = WorkspaceWorkflowImprovementApproval.validate(record)
        val current = listApprovals().filterNot { it.id == safe.id }
        val next = (current + safe)
            .sortedWith(
                compareBy<WorkspaceWorkflowImprovementApproval.Record> { it.approvedAtMs }
                    .thenBy { it.id }
            )
            .takeLast(MAX_APPROVAL_RECORDS)
        atomicWrite(approvalFile(), encodeApprovals(next))
        val reopened = listApprovals().firstOrNull { it.id == safe.id }
            ?: throw IllegalStateException("Workflow approval could not be verified after write")
        require(reopened == safe) { "Workflow approval changed after persistence" }
        return reopened
    }

    private fun decodeActivations(
        raw: String,
    ): List<WorkspaceWorkflowImprovementActivation.Record> {
        require(raw.toByteArray().size <= MAX_BYTES) {
            "Workflow activation store is too large"
        }
        val rootJson = JSONObject(raw)
        require(rootJson.getInt("schema") == SCHEMA) {
            "Unsupported workflow activation schema"
        }
        val array = rootJson.getJSONArray("records")
        require(array.length() <= MAX_ACTIVATION_RECORDS) {
            "Workflow activation record count is invalid"
        }
        val records = buildList {
            for (i in 0 until array.length()) {
                add(requireNotNull(
                    WorkspaceWorkflowImprovementActivation.fromJson(array.getJSONObject(i))
                ) { "Workflow activation record is invalid" })
            }
        }
        require(records.map { it.id }.distinct().size == records.size) {
            "Workflow activation IDs must be unique"
        }
        return records
    }

    private fun encodeActivations(
        records: List<WorkspaceWorkflowImprovementActivation.Record>,
    ): String {
        require(records.size <= MAX_ACTIVATION_RECORDS &&
            records.map { it.id }.distinct().size == records.size) {
            "Workflow activation records are invalid"
        }
        val text = JSONObject()
            .put("schema", SCHEMA)
            .put("records", JSONArray(records.map(
                WorkspaceWorkflowImprovementActivation::toJson)))
            .toString()
        require(text.toByteArray().size <= MAX_BYTES) {
            "Workflow activation store exceeds its byte bound"
        }
        return text
    }

    @Synchronized fun listActivations():
        List<WorkspaceWorkflowImprovementActivation.Record> {
        val target = activationFile()
        if (!target.exists()) return emptyList()
        require(target.isFile && target.length() in 1..MAX_BYTES) {
            "Workflow activation store is unavailable or too large"
        }
        return decodeActivations(target.readText())
    }

    @Synchronized fun recordActivation(
        record: WorkspaceWorkflowImprovementActivation.Record,
    ): WorkspaceWorkflowImprovementActivation.Record {
        val safe = WorkspaceWorkflowImprovementActivation.validate(record)
        // A separate activation gate, never an approval write, is the only caller.
        require(listApprovals().any {
            it.id == safe.approvalId &&
                it.proposalId == safe.proposalId &&
                it.candidateSignatureSha256 == safe.candidateSignatureSha256 &&
                it.evidenceSha256 == safe.evidenceSha256
        }) { "Activation has no matching persisted exact approval" }
        listActivations().firstOrNull { it.id == safe.id }?.let { return it }
        val next = (listActivations() + safe)
            .sortedWith(
                compareBy<WorkspaceWorkflowImprovementActivation.Record> { it.activatedAtMs }
                    .thenBy { it.id }
            ).takeLast(MAX_ACTIVATION_RECORDS)
        atomicWrite(activationFile(), encodeActivations(next))
        val reopened = listActivations().firstOrNull { it.id == safe.id }
            ?: throw IllegalStateException("Workflow activation was not verified after write")
        require(reopened == safe) { "Workflow activation changed after persistence" }
        return reopened
    }

    /**
     * One clearly user-authorized approval + planning activation operation.
     * Approval-only records already on disk are NEVER auto-promoted here.
     * Rebuild evidence before consent persistence and again after it; a correction always wins.
     */
    @Synchronized fun approveAndActivatePlanning(
        proposal: WorkspaceWorkflowImprovementProposal.Proposal,
        sourceTurnId: String,
        atMs: Long,
    ): WorkspaceWorkflowImprovementActivation.ApprovalActivation? {
        if (sourceTurnId.isBlank() || atMs < 0L) return null
        val beforeExperiences = list()
        val beforeFeedback = listFeedback()
        val current = WorkspaceWorkflowImprovementActivation.currentProposals(
            beforeExperiences, beforeFeedback
        ).singleOrNull { it.id == proposal.id } ?: return null
        if (current != proposal) return null
        val prospectiveApproval = WorkspaceWorkflowImprovementApproval.fromUserTurn(
            proposal = current,
            sourceTurnId = sourceTurnId,
            approvedAtMs = atMs,
        )
        // This checks ALL retained counter-evidence before recording any consent.
        val preflight = WorkspaceWorkflowImprovementActivation.issue(
            requestedProposalId = current.id,
            sourceTurnId = sourceTurnId,
            activatedAtMs = atMs,
            experiences = beforeExperiences,
            feedback = beforeFeedback,
            approvals = listOf(prospectiveApproval),
        ) ?: return null
        if (preflight.approvalId != prospectiveApproval.id) return null

        val approval = recordApproval(prospectiveApproval)
        // No historical approval can substitute for the current turn's exact consent.
        val activation = WorkspaceWorkflowImprovementActivation.issue(
            requestedProposalId = current.id,
            sourceTurnId = sourceTurnId,
            activatedAtMs = atMs,
            experiences = list(),
            feedback = listFeedback(),
            approvals = listApprovals(),
        )?.takeIf { it.approvalId == approval.id }
            ?: return WorkspaceWorkflowImprovementActivation.ApprovalActivation(
                approval = approval,
                activation = null,
            )
        val recorded = runCatching { recordActivation(activation) }.getOrNull()
        val effective = recorded?.let { safeRecord ->
            runCatching {
                WorkspaceWorkflowImprovementActivation.effective(
                    safeRecord, list(), listFeedback(), listApprovals()
                )
            }.getOrDefault(false)
        } ?: false
        return WorkspaceWorkflowImprovementActivation.ApprovalActivation(
            approval = approval,
            activation = recorded?.takeIf { effective },
        )
    }

    /**
     * Experience or feedback changes invalidate prior proposal snapshots. Delete ineffective
     * activation records from the same private owner so feedback eviction or app restart cannot
     * resurrect revoked planning guidance. Never touches approvals or execution authority.
     */
    private fun pruneInvalidPlanningActivations() {
        if (!activationFile().exists()) return
        val previous = listActivations()
        if (previous.isEmpty()) return
        val experiences = list()
        val feedback = listFeedback()
        val approvals = listApprovals()
        val valid = previous.filter {
            WorkspaceWorkflowImprovementActivation.effective(
                it, experiences, feedback, approvals
            )
        }
        if (valid.size != previous.size) {
            atomicWrite(activationFile(), encodeActivations(valid))
            require(listActivations() == valid) {
                "Workflow activation revocation could not be verified"
            }
        }
    }
}
