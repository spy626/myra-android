package com.myra.assistant.ui.workspace

import okhttp3.Request
import okhttp3.Response

/**
 * Pure bounded plan for follow-up GitHub repository reads.
 *
 * It parses only pinned path metadata, ranks locally, and prepares same-revision GETs. It executes
 * nothing, stores no credentials, and does not project any repository content to a provider.
 */
internal object WorkspaceAgentReachGitHubRelevantPlan {
    private const val MAX_TOTAL_DECLARED_BYTES = 96_000

    data class PlannedFile(
        val candidate: WorkspaceAgentReachGitHubRelevance.Candidate,
        val request: Request,
    )

    data class Plan(
        val selection: WorkspaceAgentReachGitHub.Selection,
        val commitSha: String,
        val pathMap: WorkspaceAgentReachGitHub.RepositoryPathMap,
        val files: List<PlannedFile>,
    ) {
        init {
            require(files.size <= WorkspaceAgentReachGitHubRelevance.MAX_SELECTED_FILES)
        }
    }

    fun pathMapRequest(
        target: WorkspaceAgentReachPolicy.Target,
        commitSha: String,
    ): Request {
        val selection = WorkspaceAgentReachGitHub.selection(target)
        require(selection.isRepositoryRead) {
            "Relevant-file discovery requires a GitHub repository URL"
        }
        return WorkspaceAgentReachGitHub.pathMapRequest(selection, commitSha)
    }

    fun build(
        target: WorkspaceAgentReachPolicy.Target,
        commitSha: String,
        userRequest: String,
        response: Response,
    ): Plan {
        val selection = WorkspaceAgentReachGitHub.selection(target)
        require(selection.isRepositoryRead) {
            "Relevant-file discovery requires a GitHub repository URL"
        }
        val pathMap = WorkspaceAgentReachGitHub.readPathMap(response, commitSha)
        require(pathMap.commitSha == commitSha.lowercase()) {
            "Relevant-file path map does not match the pinned GitHub revision"
        }

        var declaredBudget = 0
        val selected = WorkspaceAgentReachGitHubRelevance.select(userRequest, pathMap)
            .filter { candidate ->
                val charge = candidate.size ?: 24_000
                if (charge <= 0 || declaredBudget + charge > MAX_TOTAL_DECLARED_BYTES) {
                    false
                } else {
                    declaredBudget += charge
                    true
                }
            }

        return Plan(
            selection = selection,
            commitSha = pathMap.commitSha,
            pathMap = pathMap,
            files = selected.map { candidate ->
                PlannedFile(
                    candidate = candidate,
                    request = WorkspaceAgentReachGitHub.pinnedRepositoryFileRequest(
                        selection, pathMap.commitSha, candidate.path),
                )
            },
        )
    }
}
