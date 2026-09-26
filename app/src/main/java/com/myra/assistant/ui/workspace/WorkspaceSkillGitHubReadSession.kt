package com.myra.assistant.ui.workspace

import okhttp3.Request
import okhttp3.Response

/**
 * H8 bounded GitHub skill read state machine.
 *
 * It accepts either a repository root (root SKILL.md) or a direct GitHub SKILL.md blob/raw link.
 * The mutable ref/default branch is resolved once to an immutable commit before any skill bytes are
 * read. skill.json is optional but, when present, must come from the same directory and same SHA.
 */
internal object WorkspaceSkillGitHubReadSession {
    enum class Phase {
        AWAITING_METADATA,
        AWAITING_COMMIT,
        AWAITING_SKILL,
        AWAITING_MANIFEST,
        COMPLETE,
    }

    data class State(
        val target: WorkspaceAgentReachPolicy.Target,
        val selection: WorkspaceAgentReachGitHub.Selection,
        val skillPath: String,
        val manifestPath: String,
        val phase: Phase,
        val repositoryMeta: WorkspaceAgentReachGitHub.RepositoryMeta? = null,
        val ref: String? = null,
        val commitSha: String? = null,
        val skillEvidence: WorkspaceAgentReachEvidence.Evidence? = null,
    )

    data class Step(val state: State, val request: Request)

    data class Completion(
        val preview: WorkspaceSkillImportPreview.Preview,
        val commitSha: String,
        val sourceUrl: String,
        val manifestPresent: Boolean,
        val repositoryLicenseSpdx: String?,
        val selection: WorkspaceAgentReachGitHub.Selection,
        val skillPath: String,
        val manifestPath: String,
        val installPrepared: WorkspaceSkillInstallApproval.Prepared?,
    )

    private fun companionManifest(path: String): String =
        path.substringBeforeLast('/', "").let { dir ->
            if (dir.isBlank()) "skill.json" else dir + "/skill.json"
        }

    private fun requireSkillPath(path: String?): String {
        val clean = path.orEmpty()
        require(clean.substringAfterLast('/') == "SKILL.md") {
            "GitHub skill preview requires a repository root or direct SKILL.md link"
        }
        return clean
    }

    fun start(rawUrl: String): Step {
        val target = WorkspaceAgentReachPolicy.parse(rawUrl)
        require(target.platform == WorkspaceAgentReachPolicy.Platform.GITHUB) {
            "Skill preview requires a public GitHub URL"
        }
        val selection = WorkspaceAgentReachGitHub.selection(target)
        val skillPath = if (selection.isRepositoryRead) "SKILL.md"
        else requireSkillPath(selection.path)
        val state = State(
            target = target,
            selection = selection,
            skillPath = skillPath,
            manifestPath = companionManifest(skillPath),
            phase = if (selection.isRepositoryRead)
                Phase.AWAITING_METADATA else Phase.AWAITING_COMMIT,
            ref = if (selection.isRepositoryRead) null else selection.refHint,
        )
        val request = if (selection.isRepositoryRead) {
            WorkspaceAgentReachGitHub.repositoryMetadataRequest(selection)
        } else {
            WorkspaceAgentReachGitHub.commitRequest(
                selection,
                requireNotNull(selection.refHint) { "GitHub skill ref is unavailable" },
            )
        }
        return Step(state, request)
    }

    fun acceptMetadata(state: State, response: Response): Step {
        require(state.phase == Phase.AWAITING_METADATA && state.selection.isRepositoryRead) {
            "GitHub skill repository metadata is not expected now"
        }
        val meta = WorkspaceAgentReachGitHub.readRepositoryMeta(response)
        require(meta.fullName.equals(
            state.selection.owner + "/" + state.selection.repo,
            ignoreCase = true,
        )) { "GitHub skill repository identity changed" }
        val next = state.copy(
            phase = Phase.AWAITING_COMMIT,
            repositoryMeta = meta,
            ref = meta.defaultBranch,
        )
        return Step(
            next,
            WorkspaceAgentReachGitHub.commitRequest(state.selection, meta.defaultBranch),
        )
    }

    fun acceptCommit(state: State, response: Response): Step {
        require(state.phase == Phase.AWAITING_COMMIT) {
            "GitHub skill commit is not expected now"
        }
        val sha = WorkspaceAgentReachGitHub.readCommitSha(response)
        val next = state.copy(phase = Phase.AWAITING_SKILL, commitSha = sha)
        return Step(
            next,
            WorkspaceAgentReachGitHub.pinnedFileRequest(
                state.selection, sha, state.skillPath),
        )
    }

    fun acceptSkill(
        state: State,
        response: Response,
        fetchedAtMs: Long,
    ): Step {
        require(state.phase == Phase.AWAITING_SKILL) {
            "GitHub SKILL.md is not expected now"
        }
        val sha = requireNotNull(state.commitSha) { "Pinned GitHub skill SHA is unavailable" }
        val evidence = WorkspaceAgentReachGitHub.readPinnedFileContent(
            response = response,
            selection = state.selection,
            commitSha = sha,
            expectedPath = state.skillPath,
            fetchedAtMs = fetchedAtMs,
        )
        require(evidence.provenance.revision == sha) {
            "GitHub SKILL.md evidence revision did not match the pinned commit"
        }
        val next = state.copy(
            phase = Phase.AWAITING_MANIFEST,
            skillEvidence = evidence,
        )
        return Step(
            next,
            WorkspaceAgentReachGitHub.pinnedFileRequest(
                state.selection, sha, state.manifestPath),
        )
    }

    fun acceptManifest(
        state: State,
        response: Response,
        fetchedAtMs: Long,
    ): Completion {
        require(state.phase == Phase.AWAITING_MANIFEST) {
            "GitHub skill.json is not expected now"
        }
        val sha = requireNotNull(state.commitSha) { "Pinned GitHub skill SHA is unavailable" }
        val skillEvidence = requireNotNull(state.skillEvidence) {
            "Pinned GitHub SKILL.md evidence is unavailable"
        }

        val manifestEvidence = if (response.code == 404) {
            response.close()
            null
        } else {
            WorkspaceAgentReachGitHub.readPinnedFileContent(
                response = response,
                selection = state.selection,
                commitSha = sha,
                expectedPath = state.manifestPath,
                fetchedAtMs = fetchedAtMs,
            )
        }
        require(manifestEvidence == null || manifestEvidence.provenance.revision == sha) {
            "GitHub skill.json evidence revision did not match SKILL.md"
        }

        val provenance = WorkspaceSkillContract.Provenance(
            origin = WorkspaceSkillContract.Origin.GITHUB_PINNED,
            sourceUrl = skillEvidence.provenance.finalUrl,
            pinnedRevision = sha,
        )
        val skillMdBytes = skillEvidence.content.toByteArray(Charsets.UTF_8)
        val skillJsonBytes = manifestEvidence?.content?.toByteArray(Charsets.UTF_8)
        val preview = WorkspaceSkillImportPreview.inspect(
            skillMdBytes = skillMdBytes,
            skillJsonBytes = skillJsonBytes,
            provenance = provenance,
        )
        val installPrepared =
            if (preview.status == WorkspaceSkillImportPreview.Status.READY_FOR_INSTALL_REVIEW)
                WorkspaceSkillInstallApproval.prepare(
                    skillMdBytes = skillMdBytes,
                    skillJsonBytes = skillJsonBytes,
                    provenance = provenance,
                )
            else null
        return Completion(
            preview = preview,
            commitSha = sha,
            sourceUrl = skillEvidence.provenance.finalUrl,
            manifestPresent = manifestEvidence != null,
            repositoryLicenseSpdx = state.repositoryMeta?.licenseSpdx,
            selection = state.selection,
            skillPath = state.skillPath,
            manifestPath = state.manifestPath,
            installPrepared = installPrepared,
        )
    }
}
