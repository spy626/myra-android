package com.myra.assistant.ui.workspace

import java.util.Locale

/**
 * Connector policy is intentionally separate from AI-provider settings.
 *
 * C1 establishes verified identity/repository read access. C2 adds only broker-gated writes to the
 * already-fixed self-edit branch. Dangerous/destructive operations remain unavailable.
 */
internal object WorkspaceConnectorPolicy {
    enum class ConnectorId { GITHUB }

    enum class GitHubAction {
        READ_REPOSITORY,
        READ_COMMITS,
        READ_ACTIONS,
        CREATE_FEATURE_BRANCH,
        CREATE_OR_UPDATE_FILES,
        CREATE_OR_UPDATE_PULL_REQUEST,
        MODIFY_MAIN_OR_MASTER,
        FORCE_PUSH,
        CHANGE_SECRETS,
        DELETE_REPOSITORY_OR_BRANCH,
    }

    data class GitHubBinding(
        val repository: String,
        val branch: String,
    )

    private val ownerOrRepo = Regex("[A-Za-z0-9_.-]{1,100}")
    private val safeBranch = Regex("[A-Za-z0-9._/-]{1,200}")

    fun requireRepository(value: String): String {
        val clean = value.trim().removePrefix("https://github.com/").removeSuffix(".git")
            .trim('/')
        val parts = clean.split('/')
        require(parts.size == 2 &&
            ownerOrRepo.matches(parts[0]) &&
            ownerOrRepo.matches(parts[1]) &&
            parts.none { it == "." || it == ".." }) {
            "Repository must be owner/name"
        }
        return parts[0] + "/" + parts[1]
    }

    fun requireFeatureBranch(value: String): String {
        val clean = value.trim()
        require(safeBranch.matches(clean) &&
            !clean.startsWith("/") &&
            !clean.endsWith("/") &&
            clean.split('/').all { it.isNotBlank() && it != "." && it != ".." }) {
            "Feature branch is invalid"
        }
        require(clean.lowercase(Locale.US) !in setOf("main", "master")) {
            "main/master cannot be the LYRA self-edit branch"
        }
        return clean
    }

    fun binding(repository: String, branch: String): GitHubBinding =
        GitHubBinding(requireRepository(repository), requireFeatureBranch(branch))

    fun allowedInC1(action: GitHubAction): Boolean = when (action) {
        GitHubAction.READ_REPOSITORY,
        GitHubAction.READ_COMMITS,
        GitHubAction.READ_ACTIONS -> true
        else -> false
    }

    /**
     * C2 deliberately does not create arbitrary branches. The app is already locked to one
     * pre-existing feature branch, and writes may only advance that branch non-force.
     */
    fun allowedInC2(action: GitHubAction): Boolean = when (action) {
        GitHubAction.READ_REPOSITORY,
        GitHubAction.READ_COMMITS,
        GitHubAction.READ_ACTIONS,
        GitHubAction.CREATE_OR_UPDATE_FILES,
        GitHubAction.CREATE_OR_UPDATE_PULL_REQUEST -> true
        GitHubAction.CREATE_FEATURE_BRANCH,
        GitHubAction.MODIFY_MAIN_OR_MASTER,
        GitHubAction.FORCE_PUSH,
        GitHubAction.CHANGE_SECRETS,
        GitHubAction.DELETE_REPOSITORY_OR_BRANCH -> false
    }
}
