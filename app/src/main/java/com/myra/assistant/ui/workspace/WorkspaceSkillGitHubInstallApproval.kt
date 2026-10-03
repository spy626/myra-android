package com.myra.assistant.ui.workspace

import okhttp3.Request
import okhttp3.Response

/**
 * H9 approval-bound install revalidation for one H8 pinned GitHub skill preview.
 *
 * It never resolves the mutable branch/ref again. The positive human approval is bound to the
 * original immutable SHA, source path, package bytes, permissions and GitHub provenance.
 */
internal object WorkspaceSkillGitHubInstallApproval {
    data class Prepared(
        val skillName: String,
        val selection: WorkspaceAgentReachGitHub.Selection,
        val skillPath: String,
        val manifestPath: String,
        val commitSha: String,
        val sourceUrl: String,
        val manifestPresent: Boolean,
        val requestedCanonicalUrl: String,
        val installPrepared: WorkspaceSkillInstallApproval.Prepared,
        val approvalSummary: String,
    )

    data class RevalidationState(
        val prepared: Prepared,
        val skillEvidence: WorkspaceAgentReachEvidence.Evidence,
    )

    data class Next(
        val state: RevalidationState,
        val request: Request,
    )

    data class Validated(
        val prepared: Prepared,
        val fresh: WorkspaceSkillInstallApproval.Fresh,
    )

    fun prepare(completion: WorkspaceSkillGitHubReadSession.Completion): Prepared {
        require(
            completion.preview.status ==
                WorkspaceSkillImportPreview.Status.READY_FOR_INSTALL_REVIEW
        ) { "Blocked GitHub skill preview cannot enter install approval" }
        val install = requireNotNull(completion.installPrepared) {
            "Pinned GitHub install approval was not prepared from the inspected bytes"
        }
        require(install.skillName == completion.preview.name) {
            "Pinned GitHub install identity does not match the preview"
        }
        require(
            completion.sourceUrl ==
                completion.preview.rows.first { it.label == "Source URL" }.value &&
                completion.commitSha ==
                completion.preview.rows.first { it.label == "Pinned revision" }.value
        ) { "Pinned GitHub provenance no longer matches the preview" }

        return Prepared(
            skillName = install.skillName,
            selection = completion.selection,
            skillPath = completion.skillPath,
            manifestPath = completion.manifestPath,
            commitSha = completion.commitSha,
            sourceUrl = completion.sourceUrl,
            manifestPresent = completion.manifestPresent,
            requestedCanonicalUrl = completion.selection.requested.canonicalUrl,
            installPrepared = install,
            approvalSummary = install.approvalSummary,
        )
    }

    fun firstRequest(prepared: Prepared): Request =
        WorkspaceAgentReachGitHub.pinnedFileRequest(
            prepared.selection,
            prepared.commitSha,
            prepared.skillPath,
        )

    fun acceptSkill(
        prepared: Prepared,
        response: Response,
        fetchedAtMs: Long,
    ): Next {
        val evidence = WorkspaceAgentReachGitHub.readPinnedFileContent(
            response = response,
            selection = prepared.selection,
            commitSha = prepared.commitSha,
            expectedPath = prepared.skillPath,
            fetchedAtMs = fetchedAtMs,
        )
        require(
            evidence.provenance.revision == prepared.commitSha &&
                evidence.provenance.finalUrl == prepared.sourceUrl
        ) { "Pinned GitHub SKILL.md provenance changed after approval" }
        return Next(
            state = RevalidationState(prepared, evidence),
            request = WorkspaceAgentReachGitHub.pinnedFileRequest(
                prepared.selection,
                prepared.commitSha,
                prepared.manifestPath,
            ),
        )
    }

    fun acceptManifest(
        state: RevalidationState,
        response: Response,
        fetchedAtMs: Long,
    ): Validated {
        val prepared = state.prepared
        val manifestEvidence =
            if (prepared.manifestPresent) {
                require(response.code != 404) {
                    "Pinned skill.json disappeared after approval"
                }
                WorkspaceAgentReachGitHub.readPinnedFileContent(
                    response = response,
                    selection = prepared.selection,
                    commitSha = prepared.commitSha,
                    expectedPath = prepared.manifestPath,
                    fetchedAtMs = fetchedAtMs,
                )
            } else {
                require(response.code == 404) {
                    "A skill.json appeared after approval; preview again"
                }
                response.close()
                null
            }
        require(
            manifestEvidence == null ||
                manifestEvidence.provenance.revision == prepared.commitSha
        ) { "Pinned skill.json revision changed after approval" }

        val provenance = WorkspaceSkillContract.Provenance(
            origin = WorkspaceSkillContract.Origin.GITHUB_PINNED,
            sourceUrl = prepared.sourceUrl,
            pinnedRevision = prepared.commitSha,
        )
        val fresh = WorkspaceSkillInstallApproval.revalidate(
            prepared = prepared.installPrepared,
            skillMdBytes = state.skillEvidence.content.toByteArray(Charsets.UTF_8),
            skillJsonBytes = manifestEvidence?.content?.toByteArray(Charsets.UTF_8),
            provenance = provenance,
        )
        require(fresh.skill.provenance == provenance) {
            "Revalidated GitHub skill lost its pinned provenance"
        }
        return Validated(prepared, fresh)
    }
}
