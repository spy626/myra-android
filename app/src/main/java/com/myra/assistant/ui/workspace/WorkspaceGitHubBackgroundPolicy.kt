package com.myra.assistant.ui.workspace

internal object WorkspaceGitHubBackgroundPolicy {
    const val CHECKPOINT_SHA_KEY = "workspace_github_self_edit_checkpoint_sha"
    private val sha = Regex("[0-9a-f]{40,64}")

    fun hasCheckpoint(raw: String?): Boolean =
        raw?.trim()?.lowercase()?.let(sha::matches) == true

    fun restartDetail(hasCheckpoint: Boolean): String =
        if (hasCheckpoint) "Task paused safely · open LYRA to resume the same checkpoint"
        else "No resumable GitHub coding checkpoint"
}
