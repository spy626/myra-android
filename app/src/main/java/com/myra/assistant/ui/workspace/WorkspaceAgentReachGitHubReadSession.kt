package com.myra.assistant.ui.workspace

import okhttp3.Request
import okhttp3.Response

/**
 * Ephemeral, freshness-bound GitHub read state machine.
 *
 * It prepares one request at a time and never executes calls, retries, installs, writes or persists.
 */
internal object WorkspaceAgentReachGitHubReadSession {
    enum class Phase {
        AWAITING_METADATA,
        AWAITING_COMMIT,
        AWAITING_INDEX,
        AWAITING_CONTENT,
        COMPLETE,
    }

    data class State(
        val selection: WorkspaceAgentReachGitHub.Selection,
        val phase: Phase,
        val repositoryMeta: WorkspaceAgentReachGitHub.RepositoryMeta? = null,
        val ref: String? = null,
        val commitSha: String? = null,
        val repositoryIndex: WorkspaceAgentReachGitHub.RepositoryIndex? = null,
        val evidence: WorkspaceAgentReachEvidence.Evidence? = null,
    )

    data class Step(
        val state: State,
        val request: Request,
    )

    private fun requireCurrent(
        state: State,
        current: WorkspaceAgentReachPolicy.Target,
    ) {
        require(current.canonicalUrl == state.selection.requested.canonicalUrl &&
            current.platform == WorkspaceAgentReachPolicy.Platform.GITHUB) {
            "GitHub Agent Reach target changed; stale read result was discarded"
        }
    }

    fun start(target: WorkspaceAgentReachPolicy.Target): Step {
        val selection = WorkspaceAgentReachGitHub.selection(target)
        return if (selection.isRepositoryRead) {
            val state = State(selection, Phase.AWAITING_METADATA)
            Step(state, WorkspaceAgentReachGitHub.repositoryMetadataRequest(selection))
        } else {
            val ref = requireNotNull(selection.refHint) { "GitHub file ref is unavailable" }
            val state = State(selection, Phase.AWAITING_COMMIT, ref = ref)
            Step(state, WorkspaceAgentReachGitHub.commitRequest(selection, ref))
        }
    }

    fun acceptMetadata(
        state: State,
        current: WorkspaceAgentReachPolicy.Target,
        response: Response,
    ): Step {
        require(state.phase == Phase.AWAITING_METADATA) {
            "GitHub repository metadata is not expected in this phase"
        }
        requireCurrent(state, current)
        val meta = WorkspaceAgentReachGitHub.readRepositoryMeta(response)
        require(meta.fullName.equals(
            "${state.selection.owner}/${state.selection.repo}", ignoreCase = true)) {
            "GitHub metadata repository identity did not match the requested repository"
        }
        val ref = meta.defaultBranch
        val next = state.copy(
            phase = Phase.AWAITING_COMMIT,
            repositoryMeta = meta,
            ref = ref,
        )
        return Step(next, WorkspaceAgentReachGitHub.commitRequest(state.selection, ref))
    }

    fun acceptCommit(
        state: State,
        current: WorkspaceAgentReachPolicy.Target,
        response: Response,
    ): Step {
        require(state.phase == Phase.AWAITING_COMMIT) {
            "GitHub commit resolution is not expected in this phase"
        }
        requireCurrent(state, current)
        val sha = WorkspaceAgentReachGitHub.readCommitSha(response)
        return if (state.selection.isRepositoryRead) {
            val next = state.copy(
                phase = Phase.AWAITING_INDEX,
                commitSha = sha,
            )
            Step(next, WorkspaceAgentReachGitHub.rootIndexRequest(state.selection, sha))
        } else {
            val next = state.copy(
                phase = Phase.AWAITING_CONTENT,
                commitSha = sha,
            )
            Step(next, WorkspaceAgentReachGitHub.fileRequest(state.selection, sha))
        }
    }

    fun acceptIndex(
        state: State,
        current: WorkspaceAgentReachPolicy.Target,
        response: Response,
    ): Step {
        require(state.phase == Phase.AWAITING_INDEX && state.selection.isRepositoryRead) {
            "GitHub repository index is not expected in this phase"
        }
        requireCurrent(state, current)
        val sha = requireNotNull(state.commitSha) { "Pinned GitHub commit is unavailable" }
        val index = WorkspaceAgentReachGitHub.readRootIndex(response, sha)
        require(index.commitSha == sha) { "GitHub index revision did not match pinned commit" }
        val next = state.copy(
            phase = Phase.AWAITING_CONTENT,
            repositoryIndex = index,
        )
        return Step(next, WorkspaceAgentReachGitHub.readmeRequest(state.selection, sha))
    }

    fun acceptContent(
        state: State,
        current: WorkspaceAgentReachPolicy.Target,
        response: Response,
        fetchedAtMs: Long,
    ): State {
        require(state.phase == Phase.AWAITING_CONTENT) {
            "GitHub content is not expected in this phase"
        }
        requireCurrent(state, current)
        val sha = requireNotNull(state.commitSha) {
            "Pinned GitHub commit is unavailable"
        }
        val evidence = WorkspaceAgentReachGitHub.readContent(
            response, state.selection, sha, fetchedAtMs)
        require(evidence.provenance.revision == sha) {
            "GitHub evidence revision did not match the pinned commit"
        }
        return state.copy(
            phase = Phase.COMPLETE,
            evidence = evidence,
        )
    }
}
