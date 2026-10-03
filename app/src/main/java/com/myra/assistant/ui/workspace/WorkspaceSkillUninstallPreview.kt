package com.myra.assistant.ui.workspace

/**
 * H16 read-only uninstall preview for one exact verified installed skill.
 *
 * It creates no approval token and performs no catalog/package mutation. A retained rollback package,
 * when present, must also be freshly verified so the preview can show the full removal impact.
 */
internal object WorkspaceSkillUninstallPreview {
    data class Row(val label: String, val value: String)

    data class Preview(
        val name: String,
        val status: String,
        val summary: String,
        val rows: List<Row>,
    )

    private fun provenance(value: WorkspaceSkillContract.Provenance): String =
        buildString {
            append(value.origin.name)
            value.sourceUrl?.let { append(" · ").append(it) }
            value.pinnedRevision?.let { append(" · ").append(it) }
        }

    fun from(
        current: WorkspaceSkillStore.Installed,
        rollback: WorkspaceSkillStore.Installed?,
        dependents: List<WorkspaceSkillUninstallDependencyGuard.Dependent> = emptyList(),
    ): Preview {
        WorkspaceSkillEnablement.validateStoredState(current.entry)
        require(
            current.entry.name == current.skill.name &&
                current.entry.contentSha256 == current.skill.contentSha256 &&
                current.entry.packageSha256 == current.snapshot.packageSha256
        ) { "Current uninstall preview package identity is invalid" }

        val point = current.entry.rollbackPoint
        if (point == null) {
            require(rollback == null) { "Unexpected rollback package was supplied" }
        } else {
            requireNotNull(rollback) { "Recorded rollback package was not verified" }
            require(
                rollback.entry.name == current.entry.name &&
                    rollback.entry.packageSha256 == point.packageSha256 &&
                    rollback.entry.contentSha256 == point.contentSha256 &&
                    rollback.entry.permissionSha256 == point.permissionSha256 &&
                    rollback.entry.provenance == point.provenance &&
                    rollback.entry.installedAtMs == point.installedAtMs
            ) { "Rollback uninstall preview identity is invalid" }
        }

        val activationImpact = when (current.entry.state) {
            WorkspaceSkillCatalog.State.ENABLED ->
                "ENABLED · an approved uninstall would remove current activation authority and its " +
                    "readiness/environment/activation binding."
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED ->
                "INSTALLED_DISABLED · there is no active binding, but the installed catalog identity " +
                    "would be removed by a future approved uninstall."
        }

        val rollbackImpact = rollback?.let {
            "Verified rollback point would also stop being reachable from the skill catalog: " +
                it.entry.packageSha256
        } ?: "No rollback point is recorded for this skill."

        val packageImpact =
            if (rollback == null) {
                "This preview does not delete package bytes. If a future approved uninstall removes " +
                    "the catalog entry, the current package would become unreferenced and only the " +
                    "separate bounded retention cleanup may reclaim it."
            } else {
                "This preview does not delete package bytes. If a future approved uninstall removes " +
                    "the catalog entry, both current and rollback package directories would become " +
                    "unreferenced and only the separate bounded retention cleanup may reclaim them."
            }

        val rows = mutableListOf<Row>()
        if (dependents.isNotEmpty()) {
            rows += Row(
                "Dependency safety",
                "BLOCKED · uninstall would break installed skills that declare this skill as a dependency."
            )
            rows += Row(
                "Dependent skills",
                dependents.joinToString("\n") {
                    it.skillName + " · " + it.state.name + " · " + it.packageSha256.take(12)
                }
            )
        } else {
            rows += Row(
                "Dependency safety",
                "PASS · no other verified installed skill declares this skill as a dependency."
            )
        }
        rows += Row("Current state", current.entry.state.name)
        rows += Row("Description", current.skill.description)
        rows += Row("Content SHA-256", current.entry.contentSha256)
        rows += Row("Package SHA-256", current.entry.packageSha256)
        rows += Row("Permission SHA-256", current.entry.permissionSha256)
        rows += Row("Provenance", provenance(current.entry.provenance))
        rows += Row("Installed at", current.entry.installedAtMs.toString())
        rows += Row("Activation impact", activationImpact)
        rows += Row("Rollback impact", rollbackImpact)
        rollback?.let {
            rows += Row("Rollback package SHA-256", it.entry.packageSha256)
            rows += Row("Rollback permission SHA-256", it.entry.permissionSha256)
            rows += Row("Rollback provenance", provenance(it.entry.provenance))
        }
        rows += Row("Package retention impact", packageImpact)

        return Preview(
            name = current.entry.name,
            status = if (dependents.isEmpty()) "UNINSTALL PREVIEW"
                else "UNINSTALL BLOCKED · DEPENDENTS",
            summary = if (dependents.isEmpty()) {
                "Exact verified removal impact only. No approval token is created and no catalog " +
                    "entry, activation binding, rollback point or package directory is changed."
            } else {
                "Uninstall is blocked because another installed skill depends on this skill. " +
                    "No cascade, auto-disable, approval or catalog mutation is performed."
            },
            rows = rows,
        )
    }
}
