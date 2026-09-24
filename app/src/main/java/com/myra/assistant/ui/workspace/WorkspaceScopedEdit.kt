package com.myra.assistant.ui.workspace

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** One explicitly approved, literal project-file edit with a protected local rollback slot.
 * No model, network, personal-memory write, shell, or automatic verification authority.
 */
object WorkspaceScopedEdit {
    private const val BACKUP_NAME = "scoped-edit-backup.json"
    private const val MAX_LITERAL_CHARS = 500

    data class Proposal(
        val projectId: String, val taskId: String, val specToken: String,
        val path: String, val baseSha256: String, val resultSha256: String,
        val oldText: String, val newText: String,
    ) {
        fun displayText(): String = "ONE-FILE EDIT PROPOSAL — NOT APPLIED\n" +
            "File: $path\nOriginal SHA-256: $baseSha256\nExpected SHA-256: $resultSha256\n\n" +
            "Replace exactly once:\n$oldText\n\nWith:\n$newText\n\n" +
            "Manual literal edit, not an AI result. Separate approval is required. " +
            "No build or preview verification has run."
    }

    data class Backup(
        val projectId: String, val taskId: String, val path: String,
        val beforeSha256: String, val afterSha256: String, val original: String,
    )

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun backupFile(projects: WorkspaceProjectStore, projectId: String): File {
        require(projects.getProject(projectId) != null) { "Project unavailable" }
        val root = projects.projectRoot(projectId).canonicalFile
        val dir = File(root, ".lyra")
        require(dir.isDirectory && !Files.isSymbolicLink(dir.toPath()) &&
            dir.canonicalFile.parentFile == root) { "Unsafe project metadata" }
        return File(dir, BACKUP_NAME).also {
            require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == dir.canonicalFile) {
                "Unsafe rollback path"
            }
        }
    }

    /** Returns a persistent pending backup even if the task is subsequently paused or revoked. */
    @Synchronized fun pending(projects: WorkspaceProjectStore, projectId: String): Backup? {
        val file = backupFile(projects, projectId)
        if (!file.exists()) return null
        require(file.isFile && file.length() <= 1024 * 1024) { "Rollback data is invalid" }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        require(json.getInt("version") == 1 && json.getString("projectId") == projectId) {
            "Rollback belongs to another project"
        }
        val original = String(Base64.getDecoder().decode(json.getString("originalBase64")), Charsets.UTF_8)
        val before = json.getString("beforeSha256")
        val after = json.getString("afterSha256")
        require(original.toByteArray(Charsets.UTF_8).size <= WorkspaceFileStore.MAX_FILE_BYTES &&
            sha(original) == before && Regex("[0-9a-f]{64}").matches(after)) { "Rollback checksum is invalid" }
        val path = json.getString("path")
        require(path.isNotEmpty() && path.length <= 600) { "Rollback path is invalid" }
        return Backup(projectId, json.getString("taskId"), path, before, after, original)
    }

    /** A current approved spec + complete source fingerprint are required even for a human-typed proposal. */
    @Synchronized fun propose(
        files: WorkspaceFileStore, tasks: WorkspaceTaskStore, projects: WorkspaceProjectStore,
        context: WorkspaceSourceContext.Draft, oldText: String, newText: String,
    ): Proposal {
        require(pending(projects, context.projectId) == null) {
            "Finish the previous edit: Undo or Keep change before making another"
        }
        require(oldText.isNotEmpty() && oldText.length <= MAX_LITERAL_CHARS &&
            newText.isNotEmpty() && newText.length <= MAX_LITERAL_CHARS && oldText != newText) {
            "Enter different, non-empty exact old and new text (max 500 characters each)"
        }
        require(WorkspaceContextFreshness.check(files, tasks, context.projectId, context) ==
            WorkspaceContextFreshness.Result.SAME_CONTENT_AND_SPEC) { "Source/spec changed; choose the file again" }
        val saved = requireNotNull(tasks.get(context.projectId)) { "Saved task missing" }
        require(saved.taskId == context.taskId && WorkspaceTaskContract.specToken(saved) == context.specToken &&
            WorkspaceTaskContract.isSpecApproved(saved) && saved.status != WorkspaceTaskStatus.PAUSED) {
            "Task paused, revised or not approved for planning"
        }
        val source = files.readFile(context.projectId, context.path)
        require(sha(source) == context.fileSha256) { "File changed; select it again" }
        val start = source.indexOf(oldText)
        require(start >= 0) { "Exact old text was not found" }
        require(source.indexOf(oldText, start + 1) == -1) { "Text occurs more than once; choose a unique snippet" }
        val result = source.replaceRange(start, start + oldText.length, newText)
        require(result.toByteArray(Charsets.UTF_8).size <= WorkspaceFileStore.MAX_FILE_BYTES) {
            "Edited file would exceed 256 KB"
        }
        return Proposal(context.projectId, saved.taskId, context.specToken, context.path,
            context.fileSha256, sha(result), oldText, newText)
    }

    /** This MUST be called only from a fresh, file-specific affirmative user action. */
    @Synchronized fun apply(
        files: WorkspaceFileStore, tasks: WorkspaceTaskStore, projects: WorkspaceProjectStore,
        context: WorkspaceSourceContext.Draft, proposal: Proposal,
    ): Backup {
        require(proposal.projectId == context.projectId && proposal.taskId == context.taskId &&
            proposal.specToken == context.specToken && proposal.path == context.path &&
            proposal.baseSha256 == context.fileSha256) { "Approval is for a different file or spec" }
        val fresh = propose(files, tasks, projects, context, proposal.oldText, proposal.newText)
        require(fresh == proposal) { "Proposed edit changed; review the new proposal" }
        val source = files.readFile(proposal.projectId, proposal.path)
        require(sha(source) == proposal.baseSha256) { "File changed before saving; no edit applied" }
        val changed = source.replaceRange(source.indexOf(proposal.oldText),
            source.indexOf(proposal.oldText) + proposal.oldText.length, proposal.newText)
        val backup = Backup(proposal.projectId, proposal.taskId, proposal.path,
            proposal.baseSha256, proposal.resultSha256, source)
        // Persist before touching the source: a crash between these steps leaves a recoverable record.
        val file = backupFile(projects, proposal.projectId)
        val json = JSONObject().put("version", 1).put("projectId", backup.projectId)
            .put("taskId", backup.taskId).put("path", backup.path)
            .put("beforeSha256", backup.beforeSha256).put("afterSha256", backup.afterSha256)
            .put("originalBase64", Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_8)))
        val temp = File(file.parentFile, ".lyra-write-${UUID.randomUUID()}")
        try {
            FileOutputStream(temp).use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            require(!file.exists()) { "Rollback already exists; finish it first" }
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath()) }
        } finally { temp.delete() }
        // No false success on a stale source or a failed write. The backup remains for recovery.
        require(sha(files.readFile(proposal.projectId, proposal.path)) == proposal.baseSha256) {
            "File changed after backup; rollback record preserved, no automatic overwrite"
        }
        files.saveFile(proposal.projectId, proposal.path, changed)
        check(sha(files.readFile(proposal.projectId, proposal.path)) == backup.afterSha256) {
            "Edited file could not be verified; rollback record preserved"
        }
        return backup
    }

    /**
     * One bounded local recovery for a write that was not observed after approval.
     * It never calls a provider and writes only when the file is still byte-for-byte at the
     * approved base state and the durable rollback record exactly matches this proposal.
     */
    @Synchronized fun recoverExpectedWrite(
        files: WorkspaceFileStore,
        tasks: WorkspaceTaskStore,
        projects: WorkspaceProjectStore,
        proposal: Proposal,
    ): Backup {
        val task = requireNotNull(tasks.get(proposal.projectId)) { "Saved task missing" }
        require(task.taskId == proposal.taskId &&
            WorkspaceTaskContract.specToken(task) == proposal.specToken &&
            WorkspaceTaskContract.isSpecApproved(task) &&
            task.status != WorkspaceTaskStatus.PAUSED) {
            "Task/spec changed; local write recovery refused"
        }
        val backup = requireNotNull(pending(projects, proposal.projectId)) {
            "No matching rollback exists; local write recovery refused"
        }
        require(backup.taskId == proposal.taskId && backup.path == proposal.path &&
            backup.beforeSha256 == proposal.baseSha256 &&
            backup.afterSha256 == proposal.resultSha256) {
            "Rollback does not match the approved edit; local write recovery refused"
        }
        val current = files.readFile(proposal.projectId, proposal.path)
        require(sha(current) == proposal.baseSha256) {
            "Source is not the approved pre-edit state; newer work will not be overwritten"
        }
        val start = current.indexOf(proposal.oldText)
        require(start >= 0 && current.indexOf(proposal.oldText, start + 1) == -1) {
            "Approved exact replacement is no longer uniquely grounded"
        }
        val changed = current.replaceRange(start, start + proposal.oldText.length, proposal.newText)
        require(sha(changed) == proposal.resultSha256) {
            "Recovered edit does not match the approved result hash"
        }
        files.saveFile(proposal.projectId, proposal.path, changed)
        check(sha(files.readFile(proposal.projectId, proposal.path)) == proposal.resultSha256) {
            "Recovered file write could not be verified; rollback retained"
        }
        return backup
    }

    /** No approval needed to restore an earlier user-authorized edit; NEVER overwrite later work. */
    @Synchronized fun undo(files: WorkspaceFileStore, projects: WorkspaceProjectStore, projectId: String): Boolean {
        val backup = requireNotNull(pending(projects, projectId)) { "No edit to undo" }
        val current = files.readFile(projectId, backup.path)
        val hash = sha(current)
        require(hash == backup.afterSha256 || hash == backup.beforeSha256) {
            "File changed since the edit; Undo refused to protect newer work"
        }
        if (hash == backup.afterSha256) {
            files.saveFile(projectId, backup.path, backup.original)
            check(sha(files.readFile(projectId, backup.path)) == backup.beforeSha256) {
                "Original file could not be verified; backup retained"
            }
        }
        check(backupFile(projects, projectId).delete()) { "Original restored; backup cleanup failed" }
        return hash == backup.afterSha256
    }

    /** Explicitly accept the changed file and release the single rollback slot. */
    @Synchronized fun keep(files: WorkspaceFileStore, projects: WorkspaceProjectStore, projectId: String) {
        val backup = requireNotNull(pending(projects, projectId)) { "No pending edit" }
        require(sha(files.readFile(projectId, backup.path)) == backup.afterSha256) {
            "File changed; review it in Files before keeping or clearing the backup"
        }
        check(backupFile(projects, projectId).delete()) { "Could not clear rollback record" }
    }
}
