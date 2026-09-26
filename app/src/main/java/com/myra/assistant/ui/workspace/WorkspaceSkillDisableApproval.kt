package com.myra.assistant.ui.workspace

import java.security.MessageDigest

/**
 * H5 approval-bound disable contract for an already enabled immutable skill package.
 *
 * Disable removes activation authority only. It does not delete package bytes, alter permissions,
 * install another version, roll back content or invoke any provider/model/tool.
 */
internal object WorkspaceSkillDisableApproval {
    private const val DISABLE_APPROVAL_PREFIX = "lyra-skill-disable-v1:"
    private val sha = Regex("""[0-9a-f]{64}""")

    data class Request(
        val skillName: String,
        val contentSha256: String,
        val packageSha256: String,
        val permissionSha256: String,
        val activationBindingSha256: String,
        val enabledAtMs: Long,
        val approvalToken: String,
    )

    data class Prepared(
        val skillName: String,
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

    private fun short(value: String): String = requireSha(value, "Skill").take(12)

    private fun request(installed: WorkspaceSkillStore.Installed): Request {
        val entry = installed.entry
        WorkspaceSkillEnablement.validateStoredState(entry)
        require(entry.state == WorkspaceSkillCatalog.State.ENABLED) {
            "Only an enabled skill can enter disable approval"
        }
        require(
            entry.name == installed.skill.name &&
                entry.contentSha256 == installed.skill.contentSha256 &&
                entry.contentSha256 == installed.snapshot.contentSha256 &&
                entry.packageSha256 == installed.snapshot.packageSha256
        ) { "Enabled skill identity no longer matches the immutable package" }

        val binding = requireSha(entry.enableBindingSha256, "Activation binding")
        val enabledAt = requireNotNull(entry.enabledAtMs)
        val material = buildString {
            appendLine("name=" + entry.name)
            appendLine("content=" + entry.contentSha256)
            appendLine("package=" + entry.packageSha256)
            appendLine("permissions=" + entry.permissionSha256)
            appendLine("activation=" + binding)
            append("enabledAt=" + enabledAt)
        }
        return Request(
            skillName = entry.name,
            contentSha256 = requireSha(entry.contentSha256, "Content"),
            packageSha256 = requireSha(entry.packageSha256, "Package"),
            permissionSha256 = requireSha(entry.permissionSha256, "Permission"),
            activationBindingSha256 = binding,
            enabledAtMs = enabledAt,
            approvalToken = DISABLE_APPROVAL_PREFIX + sha256(material),
        )
    }

    fun prepare(installed: WorkspaceSkillStore.Installed): Prepared {
        val request = request(installed)
        val summary = buildString {
            appendLine("Disable only this currently enabled activation:")
            appendLine()
            appendLine("Skill: " + request.skillName)
            appendLine("Content: " + short(request.contentSha256))
            appendLine("Package: " + short(request.packageSha256))
            appendLine("Permissions: " + short(request.permissionSha256))
            appendLine("Activation: " + short(request.activationBindingSha256))
            appendLine()
            appendLine("The immutable installed package will remain on this phone.")
            appendLine("Activation/readiness/environment bindings will be cleared.")
            append("Using the skill again will require fresh readiness and explicit enable approval.")
        }
        require(summary.length <= 2_000 && summary.none { it == '\u0000' }) {
            "Skill disable approval summary is invalid"
        }
        return Prepared(request.skillName, request, summary)
    }

    fun validate(
        installed: WorkspaceSkillStore.Installed,
        request: Request,
        approvedToken: String,
    ) {
        val expected = request(installed)
        require(request == expected && approvedToken == expected.approvalToken) {
            "Skill disable approval is stale, changed, or does not match the current activation"
        }
    }
}
