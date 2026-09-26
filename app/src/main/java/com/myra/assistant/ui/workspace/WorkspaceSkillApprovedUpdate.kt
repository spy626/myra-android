package com.myra.assistant.ui.workspace

import java.security.MessageDigest

/**
 * H11 explicit package-update approval contract.
 *
 * This contract is for a human-selected local or pinned-GitHub candidate. It is separate from
 * evidence-backed self-improvement overlay promotion, but deliberately reuses the same authoritative
 * non-widening permission rule. No provider/model can mint or satisfy this approval.
 */
internal object WorkspaceSkillApprovedUpdate {
    private const val APPROVAL_PREFIX = "lyra-skill-approved-update-v1:"
    private val sha = Regex("""[0-9a-f]{64}""")

    data class Request(
        val skillName: String,
        val fromContentSha256: String,
        val fromPackageSha256: String,
        val fromPermissionSha256: String,
        val fromProvenance: WorkspaceSkillContract.Provenance,
        val fromState: WorkspaceSkillCatalog.State,
        val fromActivationBindingSha256: String?,
        val toContentSha256: String,
        val toPackageSha256: String,
        val toPermissionSha256: String,
        val toProvenance: WorkspaceSkillContract.Provenance,
        val approvalToken: String,
    )

    data class Prepared(
        val request: Request,
        val approvalSummary: String,
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun short(value: String): String {
        require(sha.matches(value)) { "Skill update fingerprint is invalid" }
        return value.take(12)
    }

    private fun provenanceMaterial(value: WorkspaceSkillContract.Provenance): String =
        listOf(
            value.origin.name,
            value.sourceUrl.orEmpty(),
            value.pinnedRevision.orEmpty(),
        ).joinToString("|")

    private fun expectedRequest(
        current: WorkspaceSkillStore.Installed,
        candidate: WorkspaceSkillUpdatePreview.Candidate,
    ): Request {
        WorkspaceSkillEnablement.validateStoredState(current.entry)
        require(candidate.skill.name == current.skill.name) {
            "Skill update must keep the same installed name"
        }
        require(
            candidate.snapshot.skillName == candidate.skill.name &&
                candidate.snapshot.contentSha256 == candidate.skill.contentSha256
        ) { "Candidate snapshot does not match the parsed update skill" }
        val candidateApproval =
            WorkspaceSkillCatalog.approvalRequest(candidate.skill, candidate.snapshot)
        require(candidateApproval.permissionSha256 == candidate.permissionSha256) {
            "Candidate permission fingerprint changed after preview"
        }
        require(
            candidate.skill.contentSha256 != current.skill.contentSha256 ||
                candidate.snapshot.packageSha256 != current.snapshot.packageSha256
        ) { "Identical immutable package is not an update" }

        WorkspaceSkillUpdate.requireNonWidening(current.skill, candidate.skill)

        val material = buildString {
            appendLine("name=" + current.skill.name)
            appendLine("fromContent=" + current.skill.contentSha256)
            appendLine("fromPackage=" + current.snapshot.packageSha256)
            appendLine("fromPermissions=" + current.entry.permissionSha256)
            appendLine("fromProvenance=" + provenanceMaterial(current.skill.provenance))
            appendLine("fromState=" + current.entry.state.name)
            appendLine("fromActivation=" + current.entry.enableBindingSha256.orEmpty())
            appendLine("toContent=" + candidate.skill.contentSha256)
            appendLine("toPackage=" + candidate.snapshot.packageSha256)
            appendLine("toPermissions=" + candidate.permissionSha256)
            append("toProvenance=" + provenanceMaterial(candidate.skill.provenance))
        }
        return Request(
            skillName = current.skill.name,
            fromContentSha256 = current.skill.contentSha256,
            fromPackageSha256 = current.snapshot.packageSha256,
            fromPermissionSha256 = current.entry.permissionSha256,
            fromProvenance = current.skill.provenance,
            fromState = current.entry.state,
            fromActivationBindingSha256 = current.entry.enableBindingSha256,
            toContentSha256 = candidate.skill.contentSha256,
            toPackageSha256 = candidate.snapshot.packageSha256,
            toPermissionSha256 = candidate.permissionSha256,
            toProvenance = candidate.skill.provenance,
            approvalToken = APPROVAL_PREFIX + sha256(material),
        )
    }

    fun prepare(
        current: WorkspaceSkillStore.Installed,
        candidate: WorkspaceSkillUpdatePreview.Candidate,
    ): Prepared {
        val request = expectedRequest(current, candidate)
        val summary = buildString {
            appendLine("Replace only this exact installed skill package:")
            appendLine()
            appendLine("Skill: " + request.skillName)
            appendLine(
                "Current: " + short(request.fromPackageSha256) +
                    " · permissions " + short(request.fromPermissionSha256)
            )
            appendLine(
                "Candidate: " + short(request.toPackageSha256) +
                    " · permissions " + short(request.toPermissionSha256)
            )
            appendLine("Current origin: " + request.fromProvenance.origin.name)
            appendLine("Candidate origin: " + request.toProvenance.origin.name)
            request.toProvenance.sourceUrl?.let { appendLine("Candidate source: " + it) }
            request.toProvenance.pinnedRevision?.let {
                appendLine("Pinned revision: " + it)
            }
            appendLine()
            appendLine("Permissions: NON-WIDENING only.")
            appendLine(
                "Activation: current readiness/environment/activation binding will be cleared."
            )
            appendLine("Result: INSTALLED_DISABLED")
            append(
                "The previous immutable package remains on-device for a separate rollback flow."
            )
        }
        require(summary.length <= 4_000 && summary.none { it == '\u0000' }) {
            "Skill update approval summary is invalid"
        }
        return Prepared(request, summary)
    }

    fun validate(
        prepared: Prepared,
        current: WorkspaceSkillStore.Installed,
        candidate: WorkspaceSkillUpdatePreview.Candidate,
    ) {
        val expected = expectedRequest(current, candidate)
        require(prepared.request == expected) {
            "Skill update approval is stale or no longer matches current/candidate identity"
        }
    }

    fun updatedEntry(
        current: WorkspaceSkillStore.Installed,
        candidate: WorkspaceSkillUpdatePreview.Candidate,
        request: Request,
        approvedToken: String,
        updatedAtMs: Long,
    ): WorkspaceSkillCatalog.Entry {
        require(updatedAtMs >= 0L) { "Skill update timestamp is invalid" }
        val expected = expectedRequest(current, candidate)
        require(request == expected && approvedToken == expected.approvalToken) {
            "Skill update approval does not match the exact immutable transition"
        }
        return WorkspaceSkillCatalog.Entry(
            name = candidate.skill.name,
            description = candidate.skill.description,
            contentSha256 = candidate.skill.contentSha256,
            packageSha256 = candidate.snapshot.packageSha256,
            permissionSha256 = candidate.permissionSha256,
            provenance = candidate.skill.provenance,
            installedAtMs = updatedAtMs,
            state = WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
            enabledAtMs = null,
            enableReadinessSha256 = null,
            enableEnvironmentSha256 = null,
            enableBindingSha256 = null,
            rollbackPoint = WorkspaceSkillCatalog.rollbackPoint(current.entry),
        )
    }
}
