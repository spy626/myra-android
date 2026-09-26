package com.myra.assistant.ui.workspace

import java.security.MessageDigest

/**
 * H13 explicit human approval for one exact verified rollback transition.
 *
 * Rollback may intentionally restore permissions that a newer version removed, so that effect is
 * bound into the approval rather than silently treated as a normal non-widening update.
 */
internal object WorkspaceSkillApprovedRollback {
    private const val APPROVAL_PREFIX = "lyra-skill-approved-rollback-v1:"
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
        val permissionEffect: WorkspaceSkillRollbackPreview.Status,
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
        require(sha.matches(value)) { "Skill rollback fingerprint is invalid" }
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
        rollback: WorkspaceSkillStore.Installed,
    ): Request {
        WorkspaceSkillEnablement.validateStoredState(current.entry)
        require(current.skill.name == rollback.skill.name) {
            "Rollback target must keep the same installed skill name"
        }
        require(current.snapshot.packageSha256 != rollback.snapshot.packageSha256) {
            "Rollback target must differ from the current immutable package"
        }

        val recorded = current.entry.rollbackPoint
            ?: throw IllegalArgumentException("No verified rollback version is recorded")
        require(recorded == WorkspaceSkillCatalog.rollbackPoint(rollback.entry)) {
            "Rollback target no longer matches the recorded previous version"
        }

        val rollbackApproval =
            WorkspaceSkillCatalog.approvalRequest(rollback.skill, rollback.snapshot)
        require(
            rollbackApproval.permissionSha256 == rollback.entry.permissionSha256
        ) { "Rollback target permission fingerprint is invalid" }

        val preview = WorkspaceSkillRollbackPreview.compare(current, rollback)
        val material = buildString {
            appendLine("name=" + current.skill.name)
            appendLine("fromContent=" + current.skill.contentSha256)
            appendLine("fromPackage=" + current.snapshot.packageSha256)
            appendLine("fromPermissions=" + current.entry.permissionSha256)
            appendLine("fromProvenance=" + provenanceMaterial(current.skill.provenance))
            appendLine("fromState=" + current.entry.state.name)
            appendLine("fromActivation=" + current.entry.enableBindingSha256.orEmpty())
            appendLine("toContent=" + rollback.skill.contentSha256)
            appendLine("toPackage=" + rollback.snapshot.packageSha256)
            appendLine("toPermissions=" + rollback.entry.permissionSha256)
            appendLine("toProvenance=" + provenanceMaterial(rollback.skill.provenance))
            append("permissionEffect=" + preview.status.name)
        }

        return Request(
            skillName = current.skill.name,
            fromContentSha256 = current.skill.contentSha256,
            fromPackageSha256 = current.snapshot.packageSha256,
            fromPermissionSha256 = current.entry.permissionSha256,
            fromProvenance = current.skill.provenance,
            fromState = current.entry.state,
            fromActivationBindingSha256 = current.entry.enableBindingSha256,
            toContentSha256 = rollback.skill.contentSha256,
            toPackageSha256 = rollback.snapshot.packageSha256,
            toPermissionSha256 = rollback.entry.permissionSha256,
            toProvenance = rollback.skill.provenance,
            permissionEffect = preview.status,
            approvalToken = APPROVAL_PREFIX + sha256(material),
        )
    }

    fun prepare(
        current: WorkspaceSkillStore.Installed,
        rollback: WorkspaceSkillStore.Installed,
    ): Prepared {
        val request = expectedRequest(current, rollback)
        val summary = buildString {
            appendLine("Restore only this exact retained immutable version:")
            appendLine()
            appendLine("Skill: " + request.skillName)
            appendLine(
                "Current: " + short(request.fromPackageSha256) +
                    " · permissions " + short(request.fromPermissionSha256)
            )
            appendLine(
                "Rollback: " + short(request.toPackageSha256) +
                    " · permissions " + short(request.toPermissionSha256)
            )
            appendLine("Current origin: " + request.fromProvenance.origin.name)
            appendLine("Rollback origin: " + request.toProvenance.origin.name)
            request.toProvenance.sourceUrl?.let { appendLine("Rollback source: " + it) }
            request.toProvenance.pinnedRevision?.let {
                appendLine("Pinned revision: " + it)
            }
            appendLine()
            if (request.permissionEffect ==
                WorkspaceSkillRollbackPreview.Status.RESTORES_BROADER_PERMISSIONS) {
                appendLine("WARNING: RESTORES BROADER PERMISSIONS.")
                appendLine(
                    "This older version re-introduces at least one permission/invocation " +
                        "boundary removed by the current version."
                )
            } else {
                appendLine("Permission effect: no broader permission boundary is restored.")
            }
            appendLine(
                "Activation: current readiness/environment/activation binding will be discarded."
            )
            appendLine("Result: INSTALLED_DISABLED")
            append(
                "The version being replaced becomes the new one-step rollback point; " +
                    "both immutable package directories remain on-device."
            )
        }
        require(summary.length <= 4_000 && summary.none { it == '\u0000' }) {
            "Skill rollback approval summary is invalid"
        }
        return Prepared(request, summary)
    }

    fun validate(
        prepared: Prepared,
        current: WorkspaceSkillStore.Installed,
        rollback: WorkspaceSkillStore.Installed,
    ) {
        val expected = expectedRequest(current, rollback)
        require(prepared.request == expected) {
            "Skill rollback approval is stale or no longer matches the exact transition"
        }
    }

    fun rolledBackEntry(
        current: WorkspaceSkillStore.Installed,
        rollback: WorkspaceSkillStore.Installed,
        request: Request,
        approvedToken: String,
        rolledBackAtMs: Long,
    ): WorkspaceSkillCatalog.Entry {
        require(rolledBackAtMs >= 0L) { "Skill rollback timestamp is invalid" }
        val expected = expectedRequest(current, rollback)
        require(request == expected && approvedToken == expected.approvalToken) {
            "Skill rollback approval does not match the exact immutable transition"
        }

        return WorkspaceSkillCatalog.Entry(
            name = rollback.skill.name,
            description = rollback.skill.description,
            contentSha256 = rollback.skill.contentSha256,
            packageSha256 = rollback.snapshot.packageSha256,
            permissionSha256 = rollback.entry.permissionSha256,
            provenance = rollback.skill.provenance,
            installedAtMs = rolledBackAtMs,
            state = WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
            enabledAtMs = null,
            enableReadinessSha256 = null,
            enableEnvironmentSha256 = null,
            enableBindingSha256 = null,
            rollbackPoint = WorkspaceSkillCatalog.rollbackPoint(current.entry),
        )
    }
}
