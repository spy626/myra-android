package com.myra.assistant.ui.workspace

/**
 * H2 read-only detail projection for one already verified installed skill.
 *
 * It deliberately excludes SKILL.md body, provider prompts and project/memory contents. It exposes
 * only approved metadata, exact permission declarations, provenance and immutable fingerprints.
 */
internal object WorkspaceSkillReadOnlyDetail {
    data class Row(val label: String, val value: String)

    data class Detail(
        val name: String,
        val description: String,
        val stateLabel: String,
        val rows: List<Row>,
    )

    private val sha256 = Regex("""[0-9a-f]{64}""")

    private fun requireSha(value: String?, label: String): String {
        val clean = value.orEmpty()
        require(sha256.matches(clean)) { label + " fingerprint is invalid" }
        return clean
    }

    private fun values(items: Collection<String>): String =
        items.map(String::trim).filter(String::isNotBlank).sorted()
            .takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "None"

    fun from(installed: WorkspaceSkillStore.Installed): Detail {
        val entry = installed.entry
        val skill = installed.skill
        WorkspaceSkillEnablement.validateStoredState(entry)
        require(
            entry.name == skill.name &&
                entry.contentSha256 == skill.contentSha256 &&
                entry.contentSha256 == installed.snapshot.contentSha256 &&
                entry.packageSha256 == installed.snapshot.packageSha256
        ) { "Skill detail identity no longer matches the verified package" }

        val p = skill.permissionPreview
        val m = skill.manifest
        val rows = mutableListOf<Row>()

        rows += Row(
            "Origin",
            when (skill.provenance.origin) {
                WorkspaceSkillContract.Origin.USER_SUPPLIED -> "User supplied"
                WorkspaceSkillContract.Origin.GITHUB_PINNED -> "GitHub · pinned revision"
                WorkspaceSkillContract.Origin.LOCAL_DERIVED ->
                    if (skill.provenance.pinnedRevision != null) "Local derived · pinned ancestry"
                    else "Local derived"
            }
        )
        if (skill.provenance.origin != WorkspaceSkillContract.Origin.USER_SUPPLIED) {
            skill.provenance.sourceUrl?.let { rows += Row("Source", it) }
            skill.provenance.pinnedRevision?.let { rows += Row("Pinned revision", it) }
        }

        rows += Row("Declared license", skill.declaredLicense ?: "Not declared")
        rows += Row("Compatibility", skill.compatibility ?: "Not declared")
        rows += Row("Skill version", m.version ?: "Not declared")
        rows += Row("Author", m.author ?: "Not declared")
        rows += Row("Required LYRA version", m.requiredLyraVersion ?: "Not declared")
        rows += Row("Verification gate", if (skill.hasVerificationGate) "Declared" else "Missing")

        rows += Row("Allowed tools", values(p.tools))
        rows += Row("Required capabilities", values(p.capabilities))
        rows += Row("Network domains", values(p.networkDomains))
        rows += Row("Dependencies", values(p.dependencies))
        rows += Row("Source sharing", p.sourceSharing.name)
        rows += Row("Memory access", p.memoryAccess.name)
        rows += Row("User invocable", p.userInvocable.toString())
        rows += Row("Model invocable", p.modelInvocable.toString())
        rows += Row("Max nesting depth", m.maxNestingDepth.toString())
        rows += Row("Warnings", values(p.warnings))

        rows += Row("Installed at", entry.installedAtMs.toString())
        when (entry.state) {
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED -> {
                rows += Row(
                    "Activation",
                    "Disabled · fresh readiness verification and explicit approval are required"
                )
            }
            WorkspaceSkillCatalog.State.ENABLED -> {
                rows += Row("Enabled at", requireNotNull(entry.enabledAtMs).toString())
                rows += Row(
                    "Readiness SHA-256",
                    requireSha(entry.enableReadinessSha256, "Readiness")
                )
                rows += Row(
                    "Environment SHA-256",
                    requireSha(entry.enableEnvironmentSha256, "Environment")
                )
                rows += Row(
                    "Activation binding SHA-256",
                    requireSha(entry.enableBindingSha256, "Activation binding")
                )
            }
        }

        rows += Row("Content SHA-256", requireSha(entry.contentSha256, "Content"))
        rows += Row("Package SHA-256", requireSha(entry.packageSha256, "Package"))
        rows += Row("Permission SHA-256", requireSha(entry.permissionSha256, "Permission"))

        return Detail(
            name = entry.name,
            description = entry.description,
            stateLabel = when (entry.state) {
                WorkspaceSkillCatalog.State.INSTALLED_DISABLED -> "INSTALLED · DISABLED"
                WorkspaceSkillCatalog.State.ENABLED -> "ENABLED"
            },
            rows = rows.toList(),
        )
    }
}
