package com.myra.assistant.ui.workspace

import java.security.MessageDigest

/**
 * Explicit immutable same-name skill version transition.
 *
 * This does not generate candidate bytes, auto-enable a skill, execute instructions or delete the
 * previous content-addressed package. It proves only that an already parsed candidate is a
 * non-widening update backed by the current overlay evidence and explicit user approval.
 */
internal object WorkspaceSkillUpdate {
    private const val APPROVAL_PREFIX = "lyra-skill-update-v1:"

    data class Request(
        val skillName: String,
        val fromContentSha256: String,
        val fromPackageSha256: String,
        val toContentSha256: String,
        val toPackageSha256: String,
        val evidenceSha256: String,
        val permissionSha256: String,
        val approvalToken: String,
        val warnings: List<String>,
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun memoryRank(value: WorkspaceSkillContract.MemoryAccess): Int = when (value) {
        WorkspaceSkillContract.MemoryAccess.NONE -> 0
        WorkspaceSkillContract.MemoryAccess.READ -> 1
        WorkspaceSkillContract.MemoryAccess.READ_WRITE -> 2
    }

    private fun sourceRank(value: WorkspaceSkillContract.SourceSharing): Int = when (value) {
        WorkspaceSkillContract.SourceSharing.NONE -> 0
        WorkspaceSkillContract.SourceSharing.BOUNDED -> 1
    }

    fun requireNonWidening(
        current: WorkspaceSkillContract.ParsedSkill,
        candidate: WorkspaceSkillContract.ParsedSkill,
    ) {
        require(candidate.name == current.name) { "Skill update must keep the same skill name" }

        val old = current.manifest
        val next = candidate.manifest
        require(next.allowedTools.all { it in old.allowedTools }) {
            "Skill update cannot add allowed tools"
        }
        require(next.requiredCapabilities.all { it in old.requiredCapabilities }) {
            "Skill update cannot add required capabilities"
        }
        require(next.networkDomains.all { it in old.networkDomains }) {
            "Skill update cannot add network domains"
        }
        require(next.dependencySkills.all { it in old.dependencySkills }) {
            "Skill update cannot add dependency skills"
        }
        require(sourceRank(next.sourceSharing) <= sourceRank(old.sourceSharing)) {
            "Skill update cannot widen project-source sharing"
        }
        require(memoryRank(next.memoryAccess) <= memoryRank(old.memoryAccess)) {
            "Skill update cannot widen memory access"
        }
        require(!next.userInvocable || old.userInvocable) {
            "Skill update cannot newly grant user invocation"
        }
        require(!next.modelInvocable || old.modelInvocable) {
            "Skill update cannot newly grant model invocation"
        }
        require(next.maxNestingDepth <= old.maxNestingDepth) {
            "Skill update cannot increase nesting depth"
        }
    }

    fun request(
        current: WorkspaceSkillStore.Installed,
        candidate: WorkspaceSkillContract.ParsedSkill,
        candidateSnapshot: WorkspaceSkillCatalog.PackageSnapshot,
        promotion: WorkspaceSkillOverlayPromotion.Promotion,
    ): Request {
        requireNonWidening(current.skill, candidate)
        require(candidate.contentSha256 != current.skill.contentSha256 ||
            candidateSnapshot.packageSha256 != current.snapshot.packageSha256) {
            "Skill update candidate is identical to the installed package"
        }
        require(candidateSnapshot.skillName == candidate.name &&
            candidateSnapshot.contentSha256 == candidate.contentSha256) {
            "Skill update candidate snapshot does not match the parsed skill"
        }
        require(promotion.effectiveView.baseContentSha256 == current.skill.contentSha256) {
            "Skill update promotion is not bound to the installed base skill"
        }

        val candidateApproval = WorkspaceSkillCatalog.approvalRequest(candidate, candidateSnapshot)
        val material = buildString {
            appendLine("name=${candidate.name}")
            appendLine("fromContent=${current.skill.contentSha256}")
            appendLine("fromPackage=${current.snapshot.packageSha256}")
            appendLine("toContent=${candidate.contentSha256}")
            appendLine("toPackage=${candidateSnapshot.packageSha256}")
            appendLine("evidence=${promotion.evidenceSha256}")
            append("permissions=${candidateApproval.permissionSha256}")
        }
        return Request(
            skillName = candidate.name,
            fromContentSha256 = current.skill.contentSha256,
            fromPackageSha256 = current.snapshot.packageSha256,
            toContentSha256 = candidate.contentSha256,
            toPackageSha256 = candidateSnapshot.packageSha256,
            evidenceSha256 = promotion.evidenceSha256,
            permissionSha256 = candidateApproval.permissionSha256,
            approvalToken = APPROVAL_PREFIX + sha256(material),
            warnings = buildList {
                addAll(candidate.permissionPreview.warnings)
                add("Updating replaces the active catalog entry with a new immutable package.")
                add("The updated skill starts disabled and must pass enablement again.")
                add("The previous content-addressed package remains on-device for a separate rollback flow.")
            },
        )
    }

    fun updatedEntry(
        current: WorkspaceSkillStore.Installed,
        candidate: WorkspaceSkillContract.ParsedSkill,
        candidateSnapshot: WorkspaceSkillCatalog.PackageSnapshot,
        promotion: WorkspaceSkillOverlayPromotion.Promotion,
        request: Request,
        approvedToken: String,
        updatedAtMs: Long,
    ): WorkspaceSkillCatalog.Entry {
        require(updatedAtMs >= 0L) { "Skill update timestamp is invalid" }
        val expected = request(current, candidate, candidateSnapshot, promotion)
        require(request == expected && approvedToken == expected.approvalToken) {
            "Skill update approval does not match the exact immutable transition"
        }
        return WorkspaceSkillCatalog.Entry(
            name = candidate.name,
            description = candidate.description,
            contentSha256 = candidate.contentSha256,
            packageSha256 = candidateSnapshot.packageSha256,
            permissionSha256 = expected.permissionSha256,
            provenance = candidate.provenance,
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
