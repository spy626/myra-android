package com.myra.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Pizza Bot-style durable task progress RECEIPTS for the existing general runtime.
 *
 * Persist only validated IDs, step status and observed verification markers.
 * No user utterances, screenshots, credentials, URLs, page contents, memory facts,
 * action arguments, approvals or executable plan are ever serialized.
 * A process restart yields a read-only review hint, NEVER an executable task.
 */
class GeneralTaskCheckpointStore(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Checkpoint(
        val taskId: String,
        val sourceTurnId: Long,
        val planRevision: Int,
        val status: AgentRuntimeStatus,
        val lastVerifiedStepIds: List<String>,
        val needsFreshObservation: Boolean,
        val verifiedComplete: Boolean,
        val savedAtMs: Long,
        val safeSearchQuery: String? = null,
    ) {
        val requiresReview: Boolean get() =
            status !in setOf(AgentRuntimeStatus.COMPLETED, AgentRuntimeStatus.FAILED,
                AgentRuntimeStatus.CANCELLED) && needsFreshObservation
        val mayAutoResume: Boolean get() = false
    }

    companion object {
        private const val SCHEMA = 1
        private const val MAX_BYTES = 4_096L
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private val validId = Regex("""[A-Za-z0-9_-]{1,100}""")
    }

    private fun path(): File {
        require(directory.mkdirs() || directory.isDirectory) { "Checkpoint root is unavailable" }
        require(!Files.isSymbolicLink(directory.toPath())) { "Checkpoint directory is a symlink" }
        val base = directory.canonicalFile
        val target = File(base, "general-task-checkpoint.json")
        require(!Files.isSymbolicLink(target.toPath()) &&
            target.canonicalFile.parentFile == base) { "Unsafe checkpoint path" }
        return target
    }

    @Synchronized fun save(receipt: UnifiedTaskReceipt) =
        saveWithSafeSearch(receipt, null)

    /** Public research query only, never full transcript, plan, or tool arguments. */
    @Synchronized fun saveWithSafeSearch(receipt: UnifiedTaskReceipt, safeQuery: String?) {
        require(validId.matches(receipt.taskId) && receipt.sourceTurnId >= 0 &&
            receipt.planRevision in 0..1_000_000) { "Invalid task identity" }
        val stepIds = receipt.lastVerifiedStepIds.distinct().takeLast(12)
        require(stepIds.all(validId::matches)) { "Invalid verified step ID" }
        val snapshot = JSONObject()
            .put("schemaVersion", SCHEMA)
            .put("taskId", receipt.taskId)
            .put("sourceTurnId", receipt.sourceTurnId)
            .put("planRevision", receipt.planRevision)
            .put("status", receipt.status.name)
            .put("verifiedSteps", JSONArray(stepIds))
            .put("needsFreshObservation", receipt.needsFreshObservation)
            .put("verifiedComplete", receipt.verifiedComplete)
            .put("savedAtMs", now())
        val eligible = if (receipt.needsFreshObservation && !receipt.waitingForApproval)
            AutonomousRecoveryPolicy.sanitizePublicQuery(safeQuery) else null
        if (eligible != null) snapshot.put("safeSearchQuery", eligible)
        val serialized = snapshot.toString()
        require(serialized.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val destination = path()
        val temp = File(destination.parentFile, "general-task-checkpoint.tmp")
        require(!Files.isSymbolicLink(temp.toPath()) &&
            temp.canonicalFile.parentFile == destination.parentFile) { "Unsafe temp path" }
        temp.writeText(serialized, Charsets.UTF_8)
        try {
            Files.move(temp.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Explicit user acknowledgement retires the OLD interrupted task only.
     * CAS prevents dismissing a different or newer task; never grants any tool action.
     */
    @Synchronized fun closeForFreshRequest(expected: Checkpoint): Boolean {
        val existing = read() ?: return false
        if (!existing.requiresReview || existing.taskId != expected.taskId ||
            existing.sourceTurnId != expected.sourceTurnId ||
            existing.planRevision != expected.planRevision ||
            existing.savedAtMs != expected.savedAtMs) return false
        val original = path()
        val json = JSONObject(original.readText(Charsets.UTF_8))
            .put("status", AgentRuntimeStatus.CANCELLED.name)
            .put("needsFreshObservation", false)
            .put("verifiedComplete", false)
            .put("savedAtMs", now())
        json.remove("safeSearchQuery")
        val tmp = File(original.parentFile, "general-task-checkpoint.tmp")
        require(!Files.isSymbolicLink(tmp.toPath()) &&
            tmp.canonicalFile.parentFile == original.parentFile) { "Unsafe temp path" }
        tmp.writeText(json.toString(), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), original.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), original.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return true
    }

    @Synchronized fun read(): Checkpoint? = runCatching {
        val file = path()
        if (!file.isFile || file.length() !in 1..MAX_BYTES) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        if (json.getInt("schemaVersion") != SCHEMA) return null
        val id = json.getString("taskId")
        val turnId = json.getLong("sourceTurnId")
        val revision = json.getInt("planRevision")
        val savedAt = json.getLong("savedAtMs")
        if (!validId.matches(id) || turnId < 0 || revision !in 0..1_000_000 ||
            savedAt < 0 || savedAt > now() || now() - savedAt > MAX_AGE_MS) return null
        val status = AgentRuntimeStatus.valueOf(json.getString("status"))
        val rawSteps = json.getJSONArray("verifiedSteps")
        if (rawSteps.length() > 12) return null
        val steps = (0 until rawSteps.length()).map { rawSteps.getString(it) }
        if (steps.any { !validId.matches(it) } || steps.distinct().size != steps.size)
            return null
        val verified = json.getBoolean("verifiedComplete")
        // A stored flag alone cannot grant completion; only an observed step receipt
        // plus a terminal status is eligible to be reported.
        val trustworthyCompletion = verified &&
            status == AgentRuntimeStatus.COMPLETED && steps.isNotEmpty()
        val safeQuery = AutonomousRecoveryPolicy.sanitizePublicQuery(
            json.optString("safeSearchQuery"))
        Checkpoint(id, turnId, revision, status, steps,
            json.getBoolean("needsFreshObservation"), trustworthyCompletion,
            savedAt, safeQuery)
    }.getOrNull()
}
