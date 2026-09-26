package com.myra.assistant.ui.workspace

/**
 * H7 approval-bound local skill installation.
 *
 * Selected bytes remain process-local until the positive approval action. This contract binds the
 * human-readable review to the exact content/package/permission hashes produced by the existing
 * immutable skill catalog contract. Approval tokens are never rendered.
 */
internal object WorkspaceSkillInstallApproval {
    data class Prepared(
        val skillName: String,
        val contentSha256: String,
        val packageSha256: String,
        val permissionSha256: String,
        val approval: WorkspaceSkillCatalog.ApprovalRequest,
        val approvalSummary: String,
    )

    data class Fresh(
        val skill: WorkspaceSkillContract.ParsedSkill,
        val packageFiles: Map<String, ByteArray>,
        val snapshot: WorkspaceSkillCatalog.PackageSnapshot,
        val approval: WorkspaceSkillCatalog.ApprovalRequest,
    )

    private val sha = Regex("""[0-9a-f]{64}""")

    private fun short(value: String): String {
        require(sha.matches(value)) { "Skill install fingerprint is invalid" }
        return value.take(12)
    }

    private fun fresh(
        skillMdBytes: ByteArray,
        skillJsonBytes: ByteArray?,
    ): Pair<WorkspaceSkillImportPreview.Preview, Fresh> {
        val preview = WorkspaceSkillImportPreview.inspect(skillMdBytes, skillJsonBytes)
        require(preview.status == WorkspaceSkillImportPreview.Status.READY_FOR_INSTALL_REVIEW) {
            "Blocked skill preview cannot enter installation approval"
        }

        // inspect() already enforced strict UTF-8, bounds, secret screening, manifest restrictions,
        // package paths and exact network-host policy for these same bytes.
        val skillMd = skillMdBytes.toString(Charsets.UTF_8)
        val skillJson = skillJsonBytes?.toString(Charsets.UTF_8)
        val files = linkedMapOf("SKILL.md" to skillMdBytes.copyOf())
        if (skillJsonBytes != null) files["skill.json"] = skillJsonBytes.copyOf()

        val skill = WorkspaceSkillContract.parse(
            skillMd = skillMd,
            skillJson = skillJson,
            provenance = WorkspaceSkillContract.Provenance(
                WorkspaceSkillContract.Origin.USER_SUPPLIED,
            ),
            packagePaths = files.keys,
        )
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, files)
        val approval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        require(
            approval.skillName == preview.name &&
                approval.contentSha256 == skill.contentSha256 &&
                approval.packageSha256 == snapshot.packageSha256
        ) { "Install approval identity does not match the inspected local preview" }

        return preview to Fresh(
            skill = skill,
            packageFiles = files.mapValues { it.value.copyOf() },
            snapshot = snapshot,
            approval = approval,
        )
    }

    fun prepare(
        skillMdBytes: ByteArray,
        skillJsonBytes: ByteArray? = null,
    ): Prepared {
        val (preview, fresh) = fresh(skillMdBytes, skillJsonBytes)
        val p = fresh.skill.permissionPreview
        val warnings = fresh.approval.warnings
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n") { "• " + it }
            ?: "None"

        val summary = buildString {
            appendLine("Install only this exact inspected local skill:")
            appendLine()
            appendLine("Skill: " + fresh.approval.skillName)
            appendLine("Content: " + short(fresh.approval.contentSha256))
            appendLine("Package: " + short(fresh.approval.packageSha256))
            appendLine("Permissions: " + short(fresh.approval.permissionSha256))
            appendLine()
            appendLine(
                "Declared access: tools " + p.tools.size +
                    " · capabilities " + p.capabilities.size +
                    " · network " + p.networkDomains.size +
                    " · dependencies " + p.dependencies.size
            )
            appendLine(
                "Source " + p.sourceSharing.name +
                    " · memory " + p.memoryAccess.name +
                    " · model-invocable " + p.modelInvocable
            )
            appendLine()
            appendLine("Warnings:")
            appendLine(warnings)
            appendLine()
            appendLine("Result: INSTALLED_DISABLED")
            append(
                "This does not enable the skill. A later fresh readiness PASS and explicit enable " +
                    "approval are still required."
            )
        }
        require(
            preview.status == WorkspaceSkillImportPreview.Status.READY_FOR_INSTALL_REVIEW &&
                summary.length <= 4_000 &&
                summary.none { it == '\u0000' }
        ) { "Skill install approval summary is invalid" }

        return Prepared(
            skillName = fresh.approval.skillName,
            contentSha256 = fresh.approval.contentSha256,
            packageSha256 = fresh.approval.packageSha256,
            permissionSha256 = fresh.approval.permissionSha256,
            approval = fresh.approval,
            approvalSummary = summary,
        )
    }

    fun revalidate(
        prepared: Prepared,
        skillMdBytes: ByteArray,
        skillJsonBytes: ByteArray? = null,
    ): Fresh {
        val (_, fresh) = fresh(skillMdBytes, skillJsonBytes)
        require(
            prepared.skillName == fresh.approval.skillName &&
                prepared.contentSha256 == fresh.approval.contentSha256 &&
                prepared.packageSha256 == fresh.approval.packageSha256 &&
                prepared.permissionSha256 == fresh.approval.permissionSha256 &&
                prepared.approval == fresh.approval
        ) {
            "Selected skill bytes, package, permissions, or warnings changed after review"
        }
        return fresh
    }
}
