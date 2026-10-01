package com.myra.assistant.ui.workspace

/**
 * One read-only router for connected GitHub lookups.
 *
 * Evaluate the *requested object* before any self-edit route. Exact Actions run status is
 * more specific than a generic mention of commit/SHA; a negated build in a HEAD request
 * never becomes a run lookup. No result here grants write authority or starts work.
 */
internal object WorkspaceConnectedGitHubReadRouting {
    sealed interface Route {
        data class Build(val decision: WorkspaceConnectedGitHubRunIntent.Decision) : Route
        data class Download(val decision: WorkspaceConnectedGitHubDownloadIntent.Decision) : Route
        data class Heads(val decision: WorkspaceConnectedGitHubHeadsIntent.Decision) : Route
    }

    fun decide(
        userTurn: String,
        precedingChat: List<WorkspaceConversationStore.Message> = emptyList(),
    ): Route? {
        WorkspaceConnectedGitHubDownloadIntent.decide(userTurn, precedingChat)?.let {
            return Route.Download(it)
        }
        WorkspaceConnectedGitHubRunIntent.decide(userTurn)?.let {
            return Route.Build(it)
        }
        WorkspaceConnectedGitHubHeadsIntent.decide(userTurn)?.let {
            return Route.Heads(it)
        }
        return null
    }
}
