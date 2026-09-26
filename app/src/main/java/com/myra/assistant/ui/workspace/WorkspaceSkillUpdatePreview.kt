package com.myra.assistant.ui.workspace

/**
 * H10 read-only update comparison for one installed skill and one already-inspected candidate.
 *
 * It creates no approval token, promotion, update request, package write or catalog mutation.
 * The current non-widening policy remains authoritative and is reused directly for status.
 */
internal object WorkspaceSkillUpdatePreview {
    enum class Status {
        NON_WIDENING_PREVIEW,
        BLOCKED_PERMISSION_WIDENING,
        IDENTICAL_PACKAGE,
        BLOCKED_NAME_MISMATCH,
    }

    data class Candidate(
        val skill: WorkspaceSkillContract.ParsedSkill,
        val snapshot: WorkspaceSkillCatalog.PackageSnapshot,
        val permissionSha256: String,
    )

    data class SetDelta(
        val label: String,
        val added: List<String>,
        val removed: List<String>,
        val unchanged: List<String>,
    )

    data class ScalarDelta(
        val label: String,
        val current: String,
        val candidate: String,
        val classification: String,
    )

    data class Preview(
        val status: Status,
        val statusDetail: String,
        val currentName: String,
        val candidateName: String,
        val currentContentSha256: String,
        val candidateContentSha256: String,
        val currentPackageSha256: String,
        val candidatePackageSha256: String,
        val currentPermissionSha256: String,
        val candidatePermissionSha256: String,
        val currentProvenance: String,
        val candidateProvenance: String,
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
        candidate: Collection<String>,
    ): SetDelta {
        val old = current.map(String::trim).filter(String::isNotBlank).toSet()
        val next = candidate.map(String::trim).filter(String::isNotBlank).toSet()
        return SetDelta(
            label = label,
            added = (next - old).sorted(),
            removed = (old - next).sorted(),
            unchanged = old.intersect(next).sorted(),
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

    private fun rankChange(old: Int, next: Int): String = when {
        next > old -> "WIDENED"
        next < old -> "REDUCED"
        else -> "UNCHANGED"
    }

    private fun booleanGrantChange(old: Boolean, next: Boolean): String = when {
        !old && next -> "WIDENED"
        old && !next -> "REDUCED"
        else -> "UNCHANGED"
    }

    private fun scalarDeltas(
        current: WorkspaceSkillContract.ParsedSkill,
        candidate: WorkspaceSkillContract.ParsedSkill,
    ): List<ScalarDelta> {
        val old = current.manifest
        val next = candidate.manifest
        return listOf(
            ScalarDelta(
                "Source sharing",
                old.sourceSharing.name,
                next.sourceSharing.name,
                rankChange(sourceRank(old.sourceSharing), sourceRank(next.sourceSharing)),
            ),
            ScalarDelta(
                "Memory access",
                old.memoryAccess.name,
                next.memoryAccess.name,
                rankChange(memoryRank(old.memoryAccess), memoryRank(next.memoryAccess)),
            ),
            ScalarDelta(
                "User invocable",
                old.userInvocable.toString(),
                next.userInvocable.toString(),
                booleanGrantChange(old.userInvocable, next.userInvocable),
            ),
            ScalarDelta(
                "Model invocable",
                old.modelInvocable.toString(),
                next.modelInvocable.toString(),
                booleanGrantChange(old.modelInvocable, next.modelInvocable),
            ),
            ScalarDelta(
                "Max nesting depth",
                old.maxNestingDepth.toString(),
                next.maxNestingDepth.toString(),
                rankChange(old.maxNestingDepth, next.maxNestingDepth),
            ),
        )
    }

    fun candidateFromBytes(
        skillMdBytes: ByteArray,
        skillJsonBytes: ByteArray? = null,
        provenance: WorkspaceSkillContract.Provenance =
            WorkspaceSkillContract.Provenance(WorkspaceSkillContract.Origin.USER_SUPPLIED),
    ): Candidate {
        val inspected = WorkspaceSkillImportPreview.inspect(
            skillMdBytes = skillMdBytes,
            skillJsonBytes = skillJsonBytes,
            provenance = provenance,
        )
        require(
            inspected.status == WorkspaceSkillImportPreview.Status.READY_FOR_INSTALL_REVIEW
        ) { "Blocked candidate cannot enter update preview" }

        val files = linkedMapOf("SKILL.md" to skillMdBytes.copyOf())
        if (skillJsonBytes != null) files["skill.json"] = skillJsonBytes.copyOf()
        val skill = WorkspaceSkillContract.parse(
            skillMd = skillMdBytes.toString(Charsets.UTF_8),
            skillJson = skillJsonBytes?.toString(Charsets.UTF_8),
            provenance = provenance,
            packagePaths = files.keys,
        )
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, files)
        val permission = WorkspaceSkillCatalog.approvalRequest(skill, snapshot).permissionSha256
        return Candidate(skill, snapshot, permission)
    }

    fun compare(
        current: WorkspaceSkillStore.Installed,
        candidate: Candidate,
    ): Preview {
        val old = current.skill
        val next = candidate.skill

        val status = when {
            next.name != old.name -> Status.BLOCKED_NAME_MISMATCH
            next.contentSha256 == old.contentSha256 &&
                candidate.snapshot.packageSha256 == current.snapshot.packageSha256 ->
                Status.IDENTICAL_PACKAGE
            runCatching {
                WorkspaceSkillUpdate.requireNonWidening(old, next)
            }.isFailure -> Status.BLOCKED_PERMISSION_WIDENING
            else -> Status.NON_WIDENING_PREVIEW
        }

        val detail = when (status) {
            Status.NON_WIDENING_PREVIEW ->
                "Candidate is different and does not widen the current permission envelope. " +
                    "This is comparison only; no update authority has been created."
            Status.BLOCKED_PERMISSION_WIDENING ->
                "Candidate widens at least one current permission/invocation boundary. " +
                    "The existing update policy would reject this transition."
            Status.IDENTICAL_PACKAGE ->
                "Candidate content/package is identical to the installed immutable package."
            Status.BLOCKED_NAME_MISMATCH ->
                "Candidate skill name does not match the installed skill. Same-name update is required."
        }

        val activation = when (current.entry.state) {
            WorkspaceSkillCatalog.State.ENABLED ->
                "Currently ENABLED. Any accepted future update must clear the current readiness, " +
                    "environment and activation binding and start INSTALLED_DISABLED."
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED ->
                "Currently INSTALLED_DISABLED. Any accepted future update must remain disabled and " +
                    "require fresh readiness plus explicit enable approval."
        }

        return Preview(
            status = status,
            statusDetail = detail,
            currentName = old.name,
            candidateName = next.name,
            currentContentSha256 = old.contentSha256,
            candidateContentSha256 = next.contentSha256,
            currentPackageSha256 = current.snapshot.packageSha256,
            candidatePackageSha256 = candidate.snapshot.packageSha256,
            currentPermissionSha256 = current.entry.permissionSha256,
            candidatePermissionSha256 = candidate.permissionSha256,
            currentProvenance = provenance(old.provenance),
            candidateProvenance = provenance(next.provenance),
            activationImpact = activation,
            setDeltas = listOf(
                setDelta("Allowed tools", old.manifest.allowedTools, next.manifest.allowedTools),
                setDelta(
                    "Required capabilities",
                    old.manifest.requiredCapabilities,
                    next.manifest.requiredCapabilities,
                ),
                setDelta(
                    "Network domains",
                    old.manifest.networkDomains,
                    next.manifest.networkDomains,
                ),
                setDelta(
                    "Dependencies",
                    old.manifest.dependencySkills,
                    next.manifest.dependencySkills,
                ),
            ),
            scalarDeltas = scalarDeltas(old, next),
        )
    }
}
