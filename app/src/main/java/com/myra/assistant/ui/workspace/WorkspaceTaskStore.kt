package com.myra.assistant.ui.workspace

import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** One project-scoped task brief. Never writes personal AIRI memory or another Room database. */
class WorkspaceTaskStore(
    private val projects: WorkspaceProjectStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val safeId = Regex("^[A-Za-z0-9_-]{1,80}$")

    @Synchronized fun get(projectId: String): WorkspaceTask? = runCatching {
        if (projects.getProject(projectId) == null) return null
        val file = taskFile(projectId)
        if (!file.isFile) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val schema = json.getInt("schemaVersion")
        if (schema !in 1..2 || json.getString("projectId") != projectId) return null
        val id = json.getString("taskId")
        if (!safeId.matches(id)) return null
        val goal = WorkspaceTaskContract.normalizeGoal(json.getString("goal"))
        val criteria = if (schema == 1) "" else WorkspaceTaskContract.normalizeAcceptanceCriteria(json.getString("acceptanceCriteria"))
        val status = WorkspaceTaskStatus.valueOf(json.getString("status"))
        val created = json.getLong("createdAtMs")
        val updated = json.getLong("updatedAtMs")
        if (created < 0 || updated < created) return null
        WorkspaceTask(projectId, id, goal, status, created, updated, criteria)
    }.getOrNull()

    /** Replacing an existing goal needs an explicit user confirmation from the UI. */
    @Synchronized fun create(projectId: String, rawGoal: String, replaceExisting: Boolean = false,
                             rawAcceptanceCriteria: String = ""): WorkspaceTask {
        requireNotNull(projects.getProject(projectId)) { "Project is unavailable" }
        val goal = WorkspaceTaskContract.normalizeGoal(rawGoal)
        val criteria = WorkspaceTaskContract.normalizeAcceptanceCriteria(rawAcceptanceCriteria)
        require(replaceExisting || get(projectId) == null) { "Confirm before replacing the current task" }
        val id = idFactory().trim()
        require(safeId.matches(id)) { "Invalid task identity" }
        val now = nowMillis()
        return WorkspaceTask(projectId, id, goal, WorkspaceTaskStatus.DRAFT, now, now, criteria).also(::write)
    }

    /** A criteria-only edit retains the same task identity and paused state; it grants no approval. */
    @Synchronized fun updateAcceptanceCriteria(projectId: String, rawAcceptanceCriteria: String): WorkspaceTask {
        val previous = requireNotNull(get(projectId)) { "There is no saved task" }
        val criteria = WorkspaceTaskContract.normalizeAcceptanceCriteria(rawAcceptanceCriteria)
        return previous.copy(acceptanceCriteria = criteria, updatedAtMs = maxOf(nowMillis(), previous.updatedAtMs)).also(::write)
    }

    @Synchronized fun setPaused(projectId: String, paused: Boolean): WorkspaceTask {
        val previous = requireNotNull(get(projectId)) { "There is no saved task" }
        return previous.copy(status = if (paused) WorkspaceTaskStatus.PAUSED else WorkspaceTaskStatus.DRAFT,
            updatedAtMs = maxOf(nowMillis(), previous.updatedAtMs)).also(::write)
    }

    private fun write(task: WorkspaceTask) {
        requireNotNull(projects.getProject(task.projectId)) { "Project is unavailable" }
        val file = taskFile(task.projectId)
        val tmp = File(file.parentFile, "task.json.tmp")
        require(!Files.isSymbolicLink(tmp.toPath())) { "Task temporary file link is forbidden" }
        val json = JSONObject().put("schemaVersion", 2)
            .put("projectId", task.projectId).put("taskId", task.taskId)
            .put("goal", task.goal).put("acceptanceCriteria", task.acceptanceCriteria)
            .put("status", task.status.name)
            .put("createdAtMs", task.createdAtMs).put("updatedAtMs", task.updatedAtMs)
        tmp.writeText(json.toString(2), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun taskFile(projectId: String): File {
        val root = projects.projectRoot(projectId)
        require(projects.getProject(projectId) != null) { "Project is unavailable" }
        val metadata = File(root, ".lyra")
        require(metadata.isDirectory && !Files.isSymbolicLink(metadata.toPath())) { "Unsafe task metadata directory" }
        require(metadata.canonicalFile.parentFile == root.canonicalFile) { "Task metadata escaped project" }
        return File(metadata, "task.json").also {
            require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == metadata.canonicalFile) {
                "Task path escaped project"
            }
        }
    }
}
