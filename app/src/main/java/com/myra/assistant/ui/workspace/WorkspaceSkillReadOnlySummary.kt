package com.myra.assistant.ui.workspace

/**
 * Read-only projection of one verified installed skill for Settings.
 *
 * It exposes state/provenance/fingerprints only. It cannot install, enable, invoke, update,
 * promote, roll back or widen any skill permission.
 */
internal object WorkspaceSkillReadOnlySummary {
    data class Item(
        val name: String,
        val description: String,
        val stateLabel: String,
        val originLabel: String,
        val permissionSummary: String,
        val accessSummary: String,
        val activationSummary: String,
        val identitySummary: String,
    )

    private val sha256 = Regex("""[0-9a-f]{64}""")

    private fun shortHash(value: String?): String {
        val clean = value.orEmpty()
        require(sha256.matches(clean)) { "Skill fingerprint is invalid" }
        return clean.take(12)
    }

    fun from(installed: WorkspaceSkillStore.Installed): Item {
        val entry = installed.entry
        val skill = installed.skill
        WorkspaceSkillEnablement.validateStoredState(entry)
        require(
            entry.name == skill.name &&
                entry.contentSha256 == skill.contentSha256 &&
                entry.contentSha256 == installed.snapshot.contentSha256 &&
                entry.packageSha256 == installed.snapshot.packageSha256
        ) { "Skill summary identity no longer matches the verified package" }

        val permissions = skill.permissionPreview
        val origin = when (skill.provenance.origin) {
            WorkspaceSkillContract.Origin.USER_SUPPLIED -> "User supplied"
            WorkspaceSkillContract.Origin.GITHUB_PINNED -> "GitHub · pinned revision"
            WorkspaceSkillContract.Origin.LOCAL_DERIVED ->
                if (skill.provenance.pinnedRevision != null) "Local derived · pinned ancestry"
                else "Local derived"
        }
        val activation = when (entry.state) {
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED ->
                "Not active · readiness approval required before enable"
            WorkspaceSkillCatalog.State.ENABLED ->
                "Activation bound · readiness " + shortHash(entry.enableReadinessSha256) +
                    " · environment " + shortHash(entry.enableEnvironmentSha256)
        }

        return Item(
            name = entry.name,
            description = entry.description,
            stateLabel = when (entry.state) {
                WorkspaceSkillCatalog.State.INSTALLED_DISABLED -> "INSTALLED · DISABLED"
                WorkspaceSkillCatalog.State.ENABLED -> "ENABLED"
            },
            originLabel = origin,
            permissionSummary =
                "Tools " + permissions.tools.size +
                    " · Capabilities " + permissions.capabilities.size +
                    " · Network " + permissions.networkDomains.size +
                    " · Dependencies " + permissions.dependencies.size,
            accessSummary =
                "Source " + permissions.sourceSharing.name.lowercase() +
                    " · Memory " + permissions.memoryAccess.name.lowercase() +
                    " · Warnings " + permissions.warnings.size,
            activationSummary = activation,
            identitySummary =
                "content " + shortHash(entry.contentSha256) +
                    " · package " + shortHash(entry.packageSha256) +
                    " · permissions " + shortHash(entry.permissionSha256),
        )
    }
}
