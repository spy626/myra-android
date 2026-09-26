package com.myra.assistant.ui.workspace

/**
 * H12 read-only comparison between the current verified package and the one retained rollback point.
 * It creates no approval and performs no mutation.
 */
internal object WorkspaceSkillRollbackPreview {
    enum class Status { ROLLBACK_PREVIEW, RESTORES_BROADER_PERMISSIONS }

    data class SetDelta(
        val label: String,
        val restored: List<String>,
        val removed: List<String>,
        val unchanged: List<String>,
    )

    data class ScalarDelta(
        val label: String,
        val current: String,
        val rollback: String,
        val classification: String,
    )

    data class Preview(
        val status: Status,
        val currentDescription: String,
        val rollbackDescription: String,
        val currentContentSha256: String,
        val rollbackContentSha256: String,
        val currentPackageSha256: String,
        val rollbackPackageSha256: String,
        val currentPermissionSha256: String,
        val rollbackPermissionSha256: String,
        val currentProvenance: String,
        val rollbackProvenance: String,
        val currentInstalledAtMs: Long,
        val rollbackInstalledAtMs: Long,
        val activationImpact: String,
        val setDeltas: List<SetDelta>,
        val scalarDeltas: List<ScalarDelta>,
    )

    private fun provenance(value: WorkspaceSkillContract.Provenance): String =
        buildString {
            append(value.origin.name)
            value.sourceUrl?.let { append(" · ").append(it) }
            value.pinnedRevision?.let { append(" · ").append(it) }
        }

    private fun setDelta(
        label: String,
        current: Collection<String>,
        rollback: Collection<String>,
    ): SetDelta {
        val now = current.map(String::trim).filter(String::isNotBlank).toSet()
        val old = rollback.map(String::trim).filter(String::isNotBlank).toSet()
        return SetDelta(
            label,
            (old - now).sorted(),
            (now - old).sorted(),
            now.intersect(old).sorted(),
        )
    }

    private fun sourceRank(value: WorkspaceSkillContract.SourceSharing): Int = when (value) {
        WorkspaceSkillContract.SourceSharing.NONE -> 0
        WorkspaceSkillContract.SourceSharing.BOUNDED -> 1
    }

    private fun memoryRank(value: WorkspaceSkillContract.MemoryAccess): Int = when (value) {
        WorkspaceSkillContract.MemoryAccess.NONE -> 0
        WorkspaceSkillContract.MemoryAccess.READ -> 1
        WorkspaceSkillContract.MemoryAccess.READ_WRITE -> 2
    }

    private fun rankChange(current: Int, rollback: Int): String = when {
        rollback > current -> "RESTORED"
        rollback < current -> "REDUCED"
        else -> "UNCHANGED"
    }

    private fun booleanChange(current: Boolean, rollback: Boolean): String = when {
        !current && rollback -> "RESTORED"
        current && !rollback -> "REDUCED"
        else -> "UNCHANGED"
    }

    fun compare(
        current: WorkspaceSkillStore.Installed,
        rollback: WorkspaceSkillStore.Installed,
    ): Preview {
        require(current.entry.name == rollback.entry.name) {
            "Rollback version does not match the current skill name"
        }
        require(current.entry.packageSha256 != rollback.entry.packageSha256) {
            "Rollback package must differ from the current package"
        }

        val now = current.skill.manifest
        val old = rollback.skill.manifest
        val setDeltas = listOf(
            setDelta("Allowed tools", now.allowedTools, old.allowedTools),
            setDelta("Required capabilities", now.requiredCapabilities, old.requiredCapabilities),
            setDelta("Network domains", now.networkDomains, old.networkDomains),
            setDelta("Dependencies", now.dependencySkills, old.dependencySkills),
        )
        val scalarDeltas = listOf(
            ScalarDelta(
                "Source sharing", now.sourceSharing.name, old.sourceSharing.name,
                rankChange(sourceRank(now.sourceSharing), sourceRank(old.sourceSharing))),
            ScalarDelta(
                "Memory access", now.memoryAccess.name, old.memoryAccess.name,
                rankChange(memoryRank(now.memoryAccess), memoryRank(old.memoryAccess))),
            ScalarDelta(
                "User invocable", now.userInvocable.toString(), old.userInvocable.toString(),
                booleanChange(now.userInvocable, old.userInvocable)),
            ScalarDelta(
                "Model invocable", now.modelInvocable.toString(), old.modelInvocable.toString(),
                booleanChange(now.modelInvocable, old.modelInvocable)),
            ScalarDelta(
                "Max nesting depth", now.maxNestingDepth.toString(),
                old.maxNestingDepth.toString(),
                rankChange(now.maxNestingDepth, old.maxNestingDepth)),
        )
        val restoresBroader =
            setDeltas.any { it.restored.isNotEmpty() } ||
                scalarDeltas.any { it.classification == "RESTORED" }

        val activationImpact = when (current.entry.state) {
            WorkspaceSkillCatalog.State.ENABLED ->
                "Current version is ENABLED. Any future approved rollback must discard its current " +
                    "readiness/environment/activation binding and restore the older package as " +
                    "INSTALLED_DISABLED."
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED ->
                "Any future approved rollback must restore the older package as INSTALLED_DISABLED " +
                    "and require fresh readiness before enable."
        }

        return Preview(
            status = if (restoresBroader) Status.RESTORES_BROADER_PERMISSIONS
                else Status.ROLLBACK_PREVIEW,
            currentDescription = current.skill.description,
            rollbackDescription = rollback.skill.description,
            currentContentSha256 = current.skill.contentSha256,
            rollbackContentSha256 = rollback.skill.contentSha256,
            currentPackageSha256 = current.snapshot.packageSha256,
            rollbackPackageSha256 = rollback.snapshot.packageSha256,
            currentPermissionSha256 = current.entry.permissionSha256,
            rollbackPermissionSha256 = rollback.entry.permissionSha256,
            currentProvenance = provenance(current.skill.provenance),
            rollbackProvenance = provenance(rollback.skill.provenance),
            currentInstalledAtMs = current.entry.installedAtMs,
            rollbackInstalledAtMs = rollback.entry.installedAtMs,
            activationImpact = activationImpact,
            setDeltas = setDeltas,
            scalarDeltas = scalarDeltas,
        )
    }
}
