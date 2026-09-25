package com.myra.assistant.ui.workspace

import java.security.MessageDigest

/**
 * Approval-bound admission contract for instruction-only skills.
 *
 * This is deliberately not an executor and not a persistence layer. It binds the exact immutable
 * package bytes + permission preview that the user approved, and every admitted skill starts disabled.
 */
internal object WorkspaceSkillCatalog {
    internal const val MAX_PACKAGE_BYTES = 512 * 1024
    internal const val MAX_FILE_BYTES = 128 * 1024
    private const val APPROVAL_PREFIX = "lyra-skill-approve-v1:"

    enum class State { INSTALLED_DISABLED }

    data class FileDigest(
        val path: String,
        val byteCount: Int,
        val sha256: String,
    )

    data class PackageSnapshot(
        val skillName: String,
        val contentSha256: String,
        val packageSha256: String,
        val files: List<FileDigest>,
    )

    data class ApprovalRequest(
        val skillName: String,
        val contentSha256: String,
        val packageSha256: String,
        val permissionSha256: String,
        val approvalToken: String,
        val warnings: List<String>,
    )

    data class Entry(
        val name: String,
        val description: String,
        val contentSha256: String,
        val packageSha256: String,
        val permissionSha256: String,
        val provenance: WorkspaceSkillContract.Provenance,
        val installedAtMs: Long,
        val state: State = State.INSTALLED_DISABLED,
    )

    data class Catalog(
        val entries: List<Entry> = emptyList(),
    )

    private val sha = Regex("""[0-9a-f]{64}""")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    private fun packageHash(files: List<Pair<String, ByteArray>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.first }.forEachIndexed { index, (path, bytes) ->
            if (index > 0) digest.update(0)
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun canonicalPermissions(skill: WorkspaceSkillContract.ParsedSkill): String {
        val p = skill.permissionPreview
        return buildString {
            appendLine("skill=${skill.name}")
            appendLine("content=${skill.contentSha256}")
            appendLine("tools=${p.tools.sorted().joinToString(",")}")
            appendLine("capabilities=${p.capabilities.sorted().joinToString(",")}")
            appendLine("network=${p.networkDomains.sorted().joinToString(",")}")
            appendLine("source=${p.sourceSharing.name}")
            appendLine("memory=${p.memoryAccess.name}")
            appendLine("userInvocable=${p.userInvocable}")
            appendLine("modelInvocable=${p.modelInvocable}")
            appendLine("dependencies=${p.dependencies.sorted().joinToString(",")}")
            append("warnings=${p.warnings.joinToString("\u001f")}")
        }
    }

    private fun requireOriginalBytes(
        skill: WorkspaceSkillContract.ParsedSkill,
        files: Map<String, ByteArray>,
    ) {
        val skillMd = files["SKILL.md"]
            ?: throw IllegalArgumentException("Skill package is missing SKILL.md bytes")
        require(skillMd.contentEquals(skill.originalSkillMd.toByteArray(Charsets.UTF_8))) {
            "SKILL.md bytes do not match the inspected skill"
        }

        val manifestBytes = files["skill.json"]
        val inspectedManifest = skill.originalSkillJson
        if (inspectedManifest == null) {
            require(manifestBytes == null) {
                "Uninspected skill.json bytes cannot be admitted"
            }
        } else {
            require(manifestBytes != null &&
                manifestBytes.contentEquals(inspectedManifest.toByteArray(Charsets.UTF_8))) {
                "skill.json bytes do not match the inspected skill"
            }
        }
    }

    fun snapshot(
        skill: WorkspaceSkillContract.ParsedSkill,
        packageFiles: Map<String, ByteArray>,
    ): PackageSnapshot {
        require(packageFiles.isNotEmpty()) { "Skill package is empty" }
        WorkspaceSkillContract.validatePackagePaths(packageFiles.keys)
        requireOriginalBytes(skill, packageFiles)

        var total = 0L
        val normalized = packageFiles.entries.map { (rawPath, bytes) ->
            val path = rawPath.trim().replace('\\', '/')
            require(bytes.size <= MAX_FILE_BYTES) {
                "Skill package file exceeds the immutable admission bound: $path"
            }
            total += bytes.size
            require(total <= MAX_PACKAGE_BYTES) {
                "Skill package exceeds the immutable admission bound"
            }
            path to bytes.copyOf()
        }
        val digests = normalized.sortedBy { it.first }.map { (path, bytes) ->
            FileDigest(path, bytes.size, sha256(bytes))
        }
        val root = packageHash(normalized)
        require(sha.matches(root) && sha.matches(skill.contentSha256)) {
            "Skill package hashes are invalid"
        }
        return PackageSnapshot(
            skillName = skill.name,
            contentSha256 = skill.contentSha256,
            packageSha256 = root,
            files = digests,
        )
    }

    fun approvalRequest(
        skill: WorkspaceSkillContract.ParsedSkill,
        snapshot: PackageSnapshot,
    ): ApprovalRequest {
        require(snapshot.skillName == skill.name &&
            snapshot.contentSha256 == skill.contentSha256) {
            "Skill package snapshot does not match the inspected skill"
        }
        val permissionHash = sha256(canonicalPermissions(skill))
        val tokenMaterial = buildString {
            appendLine("name=${skill.name}")
            appendLine("content=${skill.contentSha256}")
            appendLine("package=${snapshot.packageSha256}")
            appendLine("permissions=$permissionHash")
            appendLine("origin=${skill.provenance.origin.name}")
            appendLine("source=${skill.provenance.sourceUrl.orEmpty()}")
            append("revision=${skill.provenance.pinnedRevision.orEmpty()}")
        }
        return ApprovalRequest(
            skillName = skill.name,
            contentSha256 = skill.contentSha256,
            packageSha256 = snapshot.packageSha256,
            permissionSha256 = permissionHash,
            approvalToken = APPROVAL_PREFIX + sha256(tokenMaterial),
            warnings = skill.permissionPreview.warnings.toList(),
        )
    }

    fun admit(
        catalog: Catalog,
        skill: WorkspaceSkillContract.ParsedSkill,
        snapshot: PackageSnapshot,
        approval: ApprovalRequest,
        approvedToken: String,
        installedAtMs: Long,
    ): Catalog {
        require(installedAtMs >= 0L) { "Skill install timestamp is invalid" }
        val expected = approvalRequest(skill, snapshot)
        require(approval == expected && approvedToken == expected.approvalToken) {
            "Skill approval does not match the exact inspected package and permissions"
        }

        val sameName = catalog.entries.firstOrNull { it.name == skill.name }
        if (sameName != null) {
            require(sameName.packageSha256 == snapshot.packageSha256 &&
                sameName.contentSha256 == skill.contentSha256 &&
                sameName.permissionSha256 == expected.permissionSha256) {
                "A different skill version already uses this name; use the separate update flow"
            }
            return catalog
        }

        val entry = Entry(
            name = skill.name,
            description = skill.description,
            contentSha256 = skill.contentSha256,
            packageSha256 = snapshot.packageSha256,
            permissionSha256 = expected.permissionSha256,
            provenance = skill.provenance,
            installedAtMs = installedAtMs,
        )
        return Catalog((catalog.entries + entry).sortedBy { it.name })
    }
}
