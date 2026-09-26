package com.myra.assistant.ui.workspace

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * H6 local skill import preview.
 *
 * This is inspection only. It does not write the skill store/catalog, create activation state,
 * persist selected bytes, invoke a provider/model/tool, or grant permissions.
 */
internal object WorkspaceSkillImportPreview {
    const val MAX_FILE_BYTES: Int = WorkspaceSkillCatalog.MAX_FILE_BYTES

    enum class Status { READY_FOR_INSTALL_REVIEW, BLOCKED_SECRET }

    data class Row(val label: String, val value: String)

    data class Preview(
        val name: String,
        val description: String,
        val status: Status,
        val statusDetail: String,
        val rows: List<Row>,
        val warnings: List<String>,
    )

    private fun strictUtf8(bytes: ByteArray, label: String): String {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_BYTES) {
            label + " is empty or exceeds the local import bound"
        }
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse {
            throw IllegalArgumentException(label + " is not valid UTF-8")
        }
    }

    private fun values(items: Collection<String>): String =
        items.map(String::trim).filter(String::isNotBlank).sorted()
            .takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "None"

    fun inspect(
        skillMdBytes: ByteArray,
        skillJsonBytes: ByteArray? = null,
    ): Preview {
        val total = skillMdBytes.size.toLong() + (skillJsonBytes?.size ?: 0)
        require(total <= WorkspaceSkillCatalog.MAX_PACKAGE_BYTES) {
            "Selected skill core files exceed the local package bound"
        }

        val skillMd = strictUtf8(skillMdBytes, "SKILL.md")
        val skillJson = skillJsonBytes?.let { strictUtf8(it, "skill.json") }
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

        val possibleSecret =
            WorkspaceSourceContext.containsPossibleSecret(skillMd) ||
                (skillJson?.let(WorkspaceSourceContext::containsPossibleSecret) ?: false)
        val status = if (possibleSecret) Status.BLOCKED_SECRET
        else Status.READY_FOR_INSTALL_REVIEW

        val p = skill.permissionPreview
        val m = skill.manifest
        val rows = buildList {
            add(Row("Origin", "User supplied · local preview only"))
            add(Row("Verification gate", if (skill.hasVerificationGate) "Declared" else "Missing"))
            add(Row("Declared license", skill.declaredLicense ?: "Not declared"))
            add(Row("Compatibility", skill.compatibility ?: "Not declared"))
            add(Row("Skill version", m.version ?: "Not declared"))
            add(Row("Author", m.author ?: "Not declared"))
            add(Row("Required LYRA version", m.requiredLyraVersion ?: "Not declared"))
            add(Row("Allowed tools", values(p.tools)))
            add(Row("Required capabilities", values(p.capabilities)))
            add(Row("Network domains", values(p.networkDomains)))
            add(Row("Dependencies", values(p.dependencies)))
            add(Row("Source sharing", p.sourceSharing.name))
            add(Row("Memory access", p.memoryAccess.name))
            add(Row("User invocable", p.userInvocable.toString()))
            add(Row("Model invocable", p.modelInvocable.toString()))
            add(Row("Max nesting depth", m.maxNestingDepth.toString()))
            add(Row("Selected files", values(files.keys)))
            add(Row("Content SHA-256", skill.contentSha256))
            add(Row("Package SHA-256", snapshot.packageSha256))
            add(Row("Permission SHA-256", approval.permissionSha256))
        }
        val warnings = buildList {
            if (possibleSecret) add(
                "Possible credential/secret material was detected. Installation review must remain blocked."
            )
            addAll(p.warnings)
        }

        return Preview(
            name = skill.name,
            description = skill.description,
            status = status,
            statusDetail = when (status) {
                Status.READY_FOR_INSTALL_REVIEW ->
                    "Core files parsed and bounded security checks passed. Nothing was installed."
                Status.BLOCKED_SECRET ->
                    "Possible secret detected. Keep this local and remove credentials before continuing."
            },
            rows = rows,
            warnings = warnings,
        )
    }
}
