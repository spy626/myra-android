package com.myra.assistant.ui.workspace

/**
 * Canonical metadata for one Workspace project.
 *
 * This state belongs to Workspace only. It is deliberately independent from
 * LYRA's personal AIRI / Plast-Mem durable-memory truth.
 */
data class WorkspaceProject(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val projectId: String,
    val name: String,
    val type: WorkspaceProjectType,
    val rootRelativePath: String,
    val activeFilePath: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastOpenedAtMs: Long,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

enum class WorkspaceProjectType(
    val storageValue: String,
    val displayName: String,
    val subtitle: String,
) {
    WEBSITE(
        storageValue = "website",
        displayName = "Website",
        subtitle = "HTML • CSS • JavaScript",
    ),
    ANDROID_APP(
        storageValue = "android_app",
        displayName = "Android App",
        subtitle = "Native Android project",
    );

    companion object {
        fun fromStorage(value: String): WorkspaceProjectType? =
            values().firstOrNull { it.storageValue == value }
    }
}
