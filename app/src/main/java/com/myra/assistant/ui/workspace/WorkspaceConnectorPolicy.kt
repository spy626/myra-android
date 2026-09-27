package com.myra.assistant.ui.workspace

import java.util.Locale

/**
 * Connector policy is intentionally separate from AI-provider settings.
 *
 * C1 only establishes a verified GitHub identity/repository binding. GitHub write execution is
 * not enabled here. Later write phases must pass these branch/action guards before networking.
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

    /**
     * C1 is verified-connect + read foundation. A future write slice may explicitly enable the
     * three safe feature-branch actions below, but protected/dangerous actions remain blocked.
     */
    fun allowedInC1(action: GitHubAction): Boolean = when (action) {
        GitHubAction.READ_REPOSITORY,
        GitHubAction.READ_COMMITS,
        GitHubAction.READ_ACTIONS -> true
        GitHubAction.CREATE_FEATURE_BRANCH,
        GitHubAction.CREATE_OR_UPDATE_FILES,
        GitHubAction.CREATE_OR_UPDATE_PULL_REQUEST,
        GitHubAction.MODIFY_MAIN_OR_MASTER,
        GitHubAction.FORCE_PUSH,
        GitHubAction.CHANGE_SECRETS,
        GitHubAction.DELETE_REPOSITORY_OR_BRANCH -> false
    }
}
