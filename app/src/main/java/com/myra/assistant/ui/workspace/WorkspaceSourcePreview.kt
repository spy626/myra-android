package com.myra.assistant.ui.workspace

/** Explicit, bounded review of one existing project file. Never an AI read or execution evidence. */
object WorkspaceSourcePreview {
    const val MAX_CHOICES = 20
    const val MAX_PREVIEW_CHARS = 1500

    data class Preview(val path: String, val excerpt: String, val truncated: Boolean) {
        fun displayText(): String = buildString {
            appendLine("READ-ONLY SOURCE PREVIEW")
            appendLine("File: $path")
            appendLine(if (truncated) "First $MAX_PREVIEW_CHARS characters only" else "Complete text in this preview")
            appendLine()
            appendLine(excerpt.ifEmpty { "(Empty file)" })
            appendLine()
            append("Local display only. No source modified, provider upload, AI run or verification. Reopen to refresh.")
        }
    }

    /** Existing FileStore owns project scoping, symlink exclusion and bounded enumeration. */
    fun choices(files: WorkspaceFileStore, projectId: String): List<String> =
        files.list(projectId).asSequence().filter { !it.folder }.take(MAX_CHOICES).map { it.path }.toList()

    fun read(files: WorkspaceFileStore, projectId: String, selectedPath: String): Preview {
        require(selectedPath in choices(files, projectId)) { "Choose a current project file from the list" }
        // FileStore enforces project-root confinement, valid UTF-8 and the 256 KB editor limit.
        val text = files.readFile(projectId, selectedPath)
        require(text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
            "This file is not readable as plain text"
        }
        return Preview(selectedPath, text.take(MAX_PREVIEW_CHARS), text.length > MAX_PREVIEW_CHARS)
    }
}
