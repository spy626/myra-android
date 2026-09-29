package com.myra.assistant.ui.workspace

/**
 * Keeps foreground chat controls independent from the protected background GitHub self-edit.
 * This is UI/concurrency policy only; it grants no repository or execution authority.
 */
internal object WorkspaceChatConcurrencyPolicy {
    enum class ComposerAction { SEND, STOP_FOREGROUND }

    data class State(
        val composerAction: ComposerAction,
        val showGitHubStop: Boolean,
        val allowNewGitHubSelfEdit: Boolean,
    )

    fun state(foregroundBusy: Boolean, githubSelfEditRunning: Boolean): State = State(
        composerAction = if (foregroundBusy) ComposerAction.STOP_FOREGROUND else ComposerAction.SEND,
        showGitHubStop = githubSelfEditRunning,
        allowNewGitHubSelfEdit = !githubSelfEditRunning,
    )

    /**
     * Background progress owns only the trace created for its originating USER turn.
     * A later foreground message must never receive or have its work trace mutated by it.
     */
    fun ownsVisibleTrace(currentTraceMessageId: String?, githubMessageId: String?): Boolean =
        githubMessageId != null &&
            (currentTraceMessageId == null || currentTraceMessageId == githubMessageId)
}
