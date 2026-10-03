package com.myra.assistant.ui.workspace

/** Pure tab projection. The Workspace file store remains the only source of project files. */
internal object WorkspaceEditorTabs {
    fun files(entries: List<WorkspaceFileStore.Entry>): List<String> =
        entries.asSequence().filterNot { it.folder }.map { it.path }.distinct().toList()

    fun label(path: String, paths: List<String>): String {
        val name = path.substringAfterLast('/')
        return if (paths.count { it.substringAfterLast('/') == name } > 1) {
            "$name · ${path.substringBeforeLast('/', "root")}" 
        } else name
    }

    fun remap(path: String?, oldPath: String, newPath: String): String? = when {
        path == null -> null
        path == oldPath || path.startsWith("$oldPath/") -> newPath + path.removePrefix(oldPath)
        else -> path
    }

    fun removed(path: String?, deletedPath: String): Boolean =
        path != null && (path == deletedPath || path.startsWith("$deletedPath/"))
}
