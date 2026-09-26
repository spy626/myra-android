package com.myra.assistant.ui.workspace

/** A bounded, local-only view of the project files. It is NOT execution or verification evidence. */
object WorkspaceProjectInspection {
    private const val DISPLAY_LIMIT = 20
    private const val PATH_DISPLAY_LIMIT = 100

    data class Snapshot(
        val fileCount: Int,
        val folderCount: Int,
        val shownPaths: List<String>,
        val hiddenListedPaths: Int,
    ) {
        fun displayText(): String = buildString {
            appendLine("READ-ONLY PROJECT SNAPSHOT")
            appendLine("$fileCount files • $folderCount folders in this bounded listing")
            if (shownPaths.isEmpty()) appendLine("No project files found yet.")
            else shownPaths.forEach { appendLine(it) }
            if (hiddenListedPaths > 0) appendLine("+$hiddenListedPaths other listed paths (not shown)")
            append("Local names only. No contents read, code edited, AI run or result verified. ")
            append("Listing is bounded; additional or deeper paths may exist. Tap Inspect again to refresh.")
        }
    }

    fun scan(files: WorkspaceFileStore, projectId: String): Snapshot {
        val entries = files.list(projectId) // Existing project-root and symlink checks own file access.
        return Snapshot(
            fileCount = entries.count { !it.folder },
            folderCount = entries.count { it.folder },
            shownPaths = entries.take(DISPLAY_LIMIT).map { entry ->
                val kind = if (entry.folder) "DIR " else "FILE"
                "$kind  ${entry.path.take(PATH_DISPLAY_LIMIT)}${if (entry.path.length > PATH_DISPLAY_LIMIT) "…" else ""}"
            },
            hiddenListedPaths = (entries.size - DISPLAY_LIMIT).coerceAtLeast(0),
        )
    }
}
