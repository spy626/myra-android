package com.myra.assistant.ui.workspace

import java.security.MessageDigest

/**
 * H17 explicit human approval for removing one exact verified skill catalog identity.
 *
 * Approval is bound to current immutable identity, activation authority and the exact retained
 * rollback identity when present. Package bytes are not part of this mutation and remain on-device.
 */
internal object WorkspaceSkillApprovedUninstall {
    private const val APPROVAL_PREFIX = "lyra-skill-approved-uninstall-v1:"
    private val sha = Regex("""[0-9a-f]{64}""")

    data class Request(
        val skillName: String,
        val contentSha256: String,
        val packageSha256: String,
        val permissionSha256: String,
        val provenance: WorkspaceSkillContract.Provenance,
        val installedAtMs: Long,
        val state: WorkspaceSkillCatalog.State,
        val enabledAtMs: Long?,
        val readinessSha256: String?,
        val environmentSha256: String?,
        val activationBindingSha256: String?,
        val rollbackContentSha256: String?,
        val rollbackPackageSha256: String?,
        val rollbackPermissionSha256: String?,
        val rollbackProvenance: WorkspaceSkillContract.Provenance?,
        val rollbackInstalledAtMs: Long?,
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

    private fun requireSha(value: String?, label: String): String {
        val clean = value.orEmpty()
        require(sha.matches(clean)) { label + " fingerprint is invalid" }
        return clean
    }

    private fun optionalSha(value: String?, label: String): String? =
        value?.let { requireSha(it, label) }

    private fun short(value: String): String = requireSha(value, "Skill").take(12)

    private fun provenanceMaterial(value: WorkspaceSkillContract.Provenance?): String =
        if (value == null) "<none>"
        else listOf(
            value.origin.name,
            value.sourceUrl.orEmpty(),
            value.pinnedRevision.orEmpty(),
        ).joinToString("|")

    private fun expectedRequest(
        current: WorkspaceSkillStore.Installed,
        rollback: WorkspaceSkillStore.Installed?,
    ): Request {
        WorkspaceSkillEnablement.validateStoredState(current.entry)
        WorkspaceSkillUninstallPreview.from(current, rollback)

        require(
            current.entry.name == current.skill.name &&
                current.entry.contentSha256 == current.skill.contentSha256 &&
                current.entry.contentSha256 == current.snapshot.contentSha256 &&
                current.entry.packageSha256 == current.snapshot.packageSha256
        ) { "Uninstall current identity no longer matches the immutable package" }

        val rollbackContent = rollback?.entry?.contentSha256
        val rollbackPackage = rollback?.entry?.packageSha256
        val rollbackPermission = rollback?.entry?.permissionSha256
        val rollbackProvenance = rollback?.entry?.provenance
        val rollbackInstalledAt = rollback?.entry?.installedAtMs

        val material = buildString {
            appendLine("name=" + current.entry.name)
            appendLine("content=" + current.entry.contentSha256)
            appendLine("package=" + current.entry.packageSha256)
            appendLine("permissions=" + current.entry.permissionSha256)
            appendLine("provenance=" + provenanceMaterial(current.entry.provenance))
            appendLine("installedAt=" + current.entry.installedAtMs)
            appendLine("state=" + current.entry.state.name)
            appendLine("enabledAt=" + (current.entry.enabledAtMs?.toString() ?: "<none>"))
            appendLine("readiness=" + current.entry.enableReadinessSha256.orEmpty())
            appendLine("environment=" + current.entry.enableEnvironmentSha256.orEmpty())
            appendLine("activation=" + current.entry.enableBindingSha256.orEmpty())
            appendLine("rollbackContent=" + rollbackContent.orEmpty())
            appendLine("rollbackPackage=" + rollbackPackage.orEmpty())
            appendLine("rollbackPermissions=" + rollbackPermission.orEmpty())
            appendLine("rollbackProvenance=" + provenanceMaterial(rollbackProvenance))
            append("rollbackInstalledAt=" + (rollbackInstalledAt?.toString() ?: "<none>"))
        }

        return Request(
            skillName = current.entry.name,
            contentSha256 = requireSha(current.entry.contentSha256, "Content"),
            packageSha256 = requireSha(current.entry.packageSha256, "Package"),
            permissionSha256 = requireSha(current.entry.permissionSha256, "Permission"),
            provenance = current.entry.provenance,
            installedAtMs = current.entry.installedAtMs,
            state = current.entry.state,
            enabledAtMs = current.entry.enabledAtMs,
            readinessSha256 = optionalSha(current.entry.enableReadinessSha256, "Readiness"),
            environmentSha256 = optionalSha(current.entry.enableEnvironmentSha256, "Environment"),
            activationBindingSha256 =
                optionalSha(current.entry.enableBindingSha256, "Activation binding"),
            rollbackContentSha256 = rollbackContent?.let { requireSha(it, "Rollback content") },
            rollbackPackageSha256 = rollbackPackage?.let { requireSha(it, "Rollback package") },
            rollbackPermissionSha256 =
                rollbackPermission?.let { requireSha(it, "Rollback permission") },
            rollbackProvenance = rollbackProvenance,
            rollbackInstalledAtMs = rollbackInstalledAt,
            approvalToken = APPROVAL_PREFIX + sha256(material),
        )
    }

    fun prepare(
        current: WorkspaceSkillStore.Installed,
        rollback: WorkspaceSkillStore.Installed?,
    ): Prepared {
        val request = expectedRequest(current, rollback)
        val summary = buildString {
            appendLine("Uninstall only this exact verified skill catalog identity:")
            appendLine()
            appendLine("Skill: " + request.skillName)
            appendLine("Current package: " + short(request.packageSha256))
            appendLine("Permissions: " + short(request.permissionSha256))
            appendLine("Current state: " + request.state.name)
            request.activationBindingSha256?.let {
                appendLine("Activation binding: " + short(it))
            }
            appendLine("Origin: " + request.provenance.origin.name)
            request.provenance.sourceUrl?.let { appendLine("Source: " + it) }
            request.provenance.pinnedRevision?.let { appendLine("Pinned revision: " + it) }
            request.rollbackPackageSha256?.let {
                appendLine("Rollback package: " + short(it))
            }
            appendLine()
            if (request.state == WorkspaceSkillCatalog.State.ENABLED) {
                appendLine("WARNING: this removes the current activation authority.")
            }
            if (request.rollbackPackageSha256 != null) {
                appendLine("The catalog rollback point will also be removed.")
            }
            appendLine("Immutable package directories will NOT be deleted by uninstall.")
            append(
                "They become unreferenced and can only be reclaimed through the separate " +
                    "bounded Skill Storage cleanup flow."
            )
        }
        require(summary.length <= 4_000 && summary.none { it == '\u0000' }) {
            "Skill uninstall approval summary is invalid"
        }
        return Prepared(request, summary)
    }

    fun validate(
        current: WorkspaceSkillStore.Installed,
        rollback: WorkspaceSkillStore.Installed?,
        request: Request,
        approvedToken: String,
    ) {
        val expected = expectedRequest(current, rollback)
        require(request == expected && approvedToken == expected.approvalToken) {
            "Skill uninstall approval is stale, changed, or does not match the exact installed identity"
        }
    }
}
