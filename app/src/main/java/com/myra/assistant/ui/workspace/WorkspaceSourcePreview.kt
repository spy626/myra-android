package com.myra.assistant.ui.workspace

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Explicit, bounded review of one existing project file. Never an AI read or execution evidence. */
object WorkspaceSourcePreview {
    const val MAX_CHOICES = 20
    const val MAX_PREVIEW_CHARS = 1500

    /** An ephemeral, locally read content snapshot; SHA-256 identifies bytes, not source trust. */
    data class Preview(
        val projectId: String,
        val path: String,
        val excerpt: String,
        val truncated: Boolean,
        val fullFileSha256: String,
        val observedAtMs: Long,
    ) {
        fun displayText(): String = buildString {
            appendLine("READ-ONLY SOURCE PREVIEW")
            appendLine("File: $path")
            appendLine(if (truncated) "First $MAX_PREVIEW_CHARS characters only" else "Complete text in this preview")
            val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(observedAtMs))
            appendLine("Local read: $utc")
            appendLine("Full-file SHA-256: $fullFileSha256")
            appendLine()
            appendLine(excerpt.ifEmpty { "(Empty file)" })
            appendLine()
            append("Local snapshot only. No source modified, provider upload, AI run or verification. Reopen to refresh; this fingerprint is not a live freshness check.")
        }
    }

    enum class ContentFreshness { SAME_CONTENT, CHANGED_CONTENT, UNAVAILABLE }

    /** Existing FileStore owns project scoping, symlink exclusion and bounded enumeration. */
    fun choices(files: WorkspaceFileStore, projectId: String): List<String> =
        files.list(projectId).asSequence().filter { !it.folder }.take(MAX_CHOICES).map { it.path }.toList()

    /** Hash the whole locally read file, not only the 1,500-character excerpt. */
    fun read(files: WorkspaceFileStore, projectId: String, selectedPath: String,
             nowMillis: () -> Long = System::currentTimeMillis): Preview {
        require(selectedPath in choices(files, projectId)) { "Choose a current project file from the list" }
        // FileStore enforces project-root confinement, valid UTF-8 and the 256 KB editor limit.
        val text = files.readFile(projectId, selectedPath)
        require(text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
            "This file is not readable as plain text"
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return Preview(projectId, selectedPath, text.take(MAX_PREVIEW_CHARS),
            text.length > MAX_PREVIEW_CHARS, digest, nowMillis())
    }

    /** Re-read using the same scoped authority. Never interpret a failed read as unchanged. */
    fun compareCurrentContent(files: WorkspaceFileStore, projectId: String, previous: Preview): ContentFreshness {
        if (previous.projectId != projectId) return ContentFreshness.UNAVAILABLE
        return runCatching {
            if (read(files, projectId, previous.path).fullFileSha256 == previous.fullFileSha256)
                ContentFreshness.SAME_CONTENT else ContentFreshness.CHANGED_CONTENT
        }.getOrDefault(ContentFreshness.UNAVAILABLE)
    }
}
