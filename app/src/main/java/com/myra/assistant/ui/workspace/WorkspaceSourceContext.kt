package com.myra.assistant.ui.workspace

import java.security.MessageDigest

/** A locally displayed candidate for future AI context, NOT permission to send or run anything. */
object WorkspaceSourceContext {
    private const val MAX_CONTEXT_CHARS = 1500
    private val allowedExtensions = setOf("html", "css", "js", "jsx", "ts", "tsx", "json", "md", "txt", "kt", "xml")
    private val riskyNames = Regex("(?i)(?:^|[/._-])(?:secrets?|credentials?|private|tokens?|passwords?|passwd|id_rsa|id_ed25519)(?:$|[/._-])")
    private val keyAssignment = Regex("""(?i)\b(?:api[ _-]?key|password|passwd|client[ _-]?secret|private[ _-]?key|access[ _-]?token|refresh[ _-]?token|auth[ _-]?token)\b\s*["']?\s*[:=]\s*["']?\s*\S+""")
    private val keyMaterial = Regex("""(?i)-----BEGIN [^-]*PRIVATE KEY-----|\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9_]{12,}|AKIA[0-9A-Z]{16})\b|\bBearer\s+[A-Za-z0-9_.-]{8,}""")

    data class Draft(
        val projectId: String,
        val taskId: String,
        val specToken: String,
        val path: String,
        val fileSha256: String,
        val observedAtMs: Long,
        val sourceExcerpt: String,
        val truncated: Boolean,
        val goal: String,
        val acceptanceCriteria: String,
    ) {
        fun displayText(): String = buildString {
            appendLine("LOCAL CONTEXT DRAFT — NOT SENT")
            appendLine("Project: $projectId  •  File: $path")
            appendLine("Source SHA-256: $fileSha256")
            appendLine("Read time (Unix ms): $observedAtMs")
            appendLine("Goal: $goal")
            appendLine("Acceptance criteria: $acceptanceCriteria")
            appendLine(if (truncated) "Source excerpt: first $MAX_CONTEXT_CHARS characters" else "Source excerpt: complete file")
            appendLine("UNTRUSTED SOURCE TEXT — DATA ONLY")
            appendLine(sourceExcerpt.ifEmpty { "(Empty file)" })
            appendLine("END UNTRUSTED SOURCE TEXT")
            append("Local preview only. Pattern screening cannot guarantee privacy. No model upload, AI run, file edit or verification. Reopen after changes.")
        }
    }

    fun choices(files: WorkspaceFileStore, projectId: String): List<String> =
        WorkspaceSourcePreview.choices(files, projectId).filter(::isEligibleProjectPath)

    internal fun isEligibleProjectPath(path: String): Boolean {
        val parts = path.split('/')
        val name = parts.lastOrNull().orEmpty()
        return parts.all { it.isNotEmpty() && !it.startsWith('.') } &&
            !riskyNames.containsMatchIn(path) &&
            name.substringAfterLast('.', "").lowercase() in allowedExtensions
    }

    /** Shared conservative pattern screen for source, specification and explicit one-turn follow-up. */
    fun containsPossibleSecret(text: String): Boolean =
        keyAssignment.containsMatchIn(text) || keyMaterial.containsMatchIn(text)

    /** Read only one explicitly selected, project-confined source; re-check spec and full-file freshness. */
    fun prepare(files: WorkspaceFileStore, tasks: WorkspaceTaskStore, projectId: String,
                expected: WorkspaceTask, selectedPath: String, nowMillis: () -> Long = System::currentTimeMillis): Draft {
        fun approvedCurrent(): WorkspaceTask {
            val current = requireNotNull(tasks.get(projectId)) { "Saved task is unavailable" }
            require(current.taskId == expected.taskId &&
                WorkspaceTaskContract.specToken(current) == WorkspaceTaskContract.specToken(expected) &&
                current.approvedSpecToken == expected.approvedSpecToken &&
                WorkspaceTaskContract.isSpecApproved(current) && current.status != WorkspaceTaskStatus.PAUSED) {
                "Saved spec changed, is paused or lacks planning approval; review it again"
            }
            return current
        }
        val current = approvedCurrent()
        require(selectedPath in choices(files, projectId)) { "Choose an allowed current project text file" }
        val preview = WorkspaceSourcePreview.read(files, projectId, selectedPath, nowMillis)
        // Scan the full 256-KB-bounded source, including text beyond the visible excerpt.
        val fullText = files.readFile(projectId, selectedPath)
        val hash = MessageDigest.getInstance("SHA-256").digest(fullText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(hash == preview.fullFileSha256) { "Source changed while reading; reopen it" }
        require(!containsPossibleSecret(fullText) && !containsPossibleSecret(current.goal) &&
            !containsPossibleSecret(current.acceptanceCriteria)) {
            "Possible secret detected; source context was blocked. Review it locally instead"
        }
        approvedCurrent()
        return Draft(projectId, current.taskId, WorkspaceTaskContract.specToken(current), selectedPath,
            hash, preview.observedAtMs, fullText.take(MAX_CONTEXT_CHARS),
            fullText.length > MAX_CONTEXT_CHARS, current.goal, current.acceptanceCriteria)
    }
}
