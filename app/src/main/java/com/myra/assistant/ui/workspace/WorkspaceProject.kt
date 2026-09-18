package com.myra.assistant.ui.workspace

/** Canonical project metadata; Workspace owns it, independently of personal AIRI memory. */
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
    companion object { const val CURRENT_SCHEMA_VERSION = 1 }
}

enum class WorkspaceProjectType(
    val storageValue: String,
    val displayName: String,
    val subtitle: String,
) {
    /** Chat-only container: no website/app is claimed, scaffolded, or shown in Projects. */
    CHAT("chat", "Chat", "Private conversation"),
    WEBSITE("website", "Website", "HTML • CSS • JavaScript"),
    ANDROID_APP("android_app", "Android App", "Native Android project");

    companion object {
        fun fromStorage(value: String): WorkspaceProjectType? =
            values().firstOrNull { it.storageValue == value }
    }
}
