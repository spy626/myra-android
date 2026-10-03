package com.myra.assistant.ui.workspace

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** One short-lived, app-private, no-backup *untrusted* AI suggestion per project.
 * The caller provides a directory under Android noBackupFilesDir. No key, prompt, model client,
 * project source, execution authority or extra database is stored here.
 */
internal class WorkspaceAiSuggestionDraftStore(
    private val directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val MAX_BYTES = 16_384L
        private const val MAX_REPLY_CHARS = 6_000
        private const val TTL_MS = 24 * 60 * 60 * 1000L
    }

    sealed class Recovery {
        data class Ready(val reply: String, val draft: WorkspaceStructuredEdit.Draft) : Recovery()
        object Missing : Recovery()
        object Rejected : Recovery()
    }

    private fun sha(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun file(projectId: String): File {
        require(projectId.isNotBlank()) { "Project is required for the temporary AI draft" }
        require(!Files.isSymbolicLink(directory.toPath())) { "Unsafe private draft directory" }
        require(directory.isDirectory || directory.mkdirs()) { "Private draft directory unavailable" }
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "Unsafe private draft directory"
        }
        return File(directory, "${sha(projectId)}.json").also {
            require(it.canonicalFile.parentFile == directory.canonicalFile) { "Unsafe private draft path" }
        }
    }

    private fun clearFile(target: File) {
        if (Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            check(Files.deleteIfExists(target.toPath())) { "Could not discard private AI draft" }
        }
    }

    @Synchronized fun discard(projectId: String) = clearFile(file(projectId))

    /** Commit only a locally validated, still-current proposal, never a raw provider response. */
    @Synchronized fun save(
        files: WorkspaceFileStore,
        tasks: WorkspaceTaskStore,
        projects: WorkspaceProjectStore,
        projectId: String,
        reply: String,
        validated: WorkspaceStructuredEdit.Draft,
    ) {
        require(reply.isNotBlank() && reply.length <= MAX_REPLY_CHARS) { "AI patch exceeds local limit" }
        val fresh = WorkspaceStructuredEdit.prepare(files, tasks, projects, projectId, reply)
        require(fresh.proposal == validated.proposal &&
            fresh.context.taskId == validated.context.taskId &&
            fresh.context.specToken == validated.context.specToken &&
            fresh.context.fileSha256 == validated.context.fileSha256 &&
            fresh.context.path == validated.context.path) { "Source, task or patch changed; no AI draft saved" }
        val timestamp = nowMillis()
        require(timestamp > 0L) { "Invalid AI draft timestamp" }
        val json = JSONObject()
            .put("version", 1)
            .put("projectId", projectId)
            .put("taskId", fresh.context.taskId)
            .put("specToken", fresh.context.specToken)
            .put("path", fresh.context.path)
            .put("sourceSha256", fresh.context.fileSha256)
            .put("savedAtMs", timestamp)
            .put("replySha256", sha(reply))
            .put("reply", reply)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAX_BYTES) { "Private AI draft exceeds local limit" }
        val target = file(projectId)
        val temp = File(directory, ".ai-draft-${UUID.randomUUID()}")
        try {
            FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
            require(!Files.isSymbolicLink(target.toPath())) { "Unsafe existing AI draft" }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temp.delete() }
        if (WorkspaceContextFreshness.check(files, tasks, projectId, fresh.context) !=
            WorkspaceContextFreshness.Result.SAME_CONTENT_AND_SPEC ||
            WorkspaceScopedEdit.pending(projects, projectId) != null) {
            clearFile(target)
            throw IllegalArgumentException("Source or task changed while saving; AI draft discarded")
        }
    }

    /** Rebuild the approved spec and full-file SHA before exposing a restored suggestion. */
    @Synchronized fun recover(
        files: WorkspaceFileStore,
        tasks: WorkspaceTaskStore,
        projects: WorkspaceProjectStore,
        projectId: String,
    ): Recovery {
        val target = file(projectId)
        if (!Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return Recovery.Missing
        return runCatching {
            require(!Files.isSymbolicLink(target.toPath()) && target.isFile &&
                target.length() in 1..MAX_BYTES) { "Invalid private AI draft" }
            val saved = JSONObject(target.readText(Charsets.UTF_8))
            require(saved.getInt("version") == 1 && saved.getString("projectId") == projectId) {
                "Private AI draft belongs to another project"
            }
            val age = nowMillis() - saved.getLong("savedAtMs")
            require(age in 0..TTL_MS) { "Private AI draft expired" }
            val reply = saved.getString("reply")
            require(reply.isNotBlank() && reply.length <= MAX_REPLY_CHARS &&
                sha(reply) == saved.getString("replySha256")) { "Private AI draft checksum failed" }
            val fresh = WorkspaceStructuredEdit.prepare(files, tasks, projects, projectId, reply)
            require(fresh.context.taskId == saved.getString("taskId") &&
                fresh.context.specToken == saved.getString("specToken") &&
                fresh.context.path == saved.getString("path") &&
                fresh.context.fileSha256 == saved.getString("sourceSha256")) {
                "Source or spec changed; private AI draft is stale"
            }
            Recovery.Ready(reply, fresh)
        }.getOrElse {
            clearFile(target)
            Recovery.Rejected
        }
    }
}
