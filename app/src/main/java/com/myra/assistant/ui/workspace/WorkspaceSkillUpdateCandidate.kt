package com.myra.assistant.ui.workspace

/**
 * Deterministically materializes one evidence-backed overlay into a new immutable skill candidate.
 *
 * Only SKILL.md description/examples may change. skill.json, references and assets are byte-for-byte
 * preserved. The result is local-derived provenance and still needs WorkspaceSkillUpdate approval.
 */
internal object WorkspaceSkillUpdateCandidate {
    private const val LEARNED_HEADING = "## LYRA Learned Examples"

    data class Candidate(
        val skill: WorkspaceSkillContract.ParsedSkill,
        val packageFiles: Map<String, ByteArray>,
        val promotion: WorkspaceSkillOverlayPromotion.Promotion,
    )

    private fun currentPackageMatches(
        current: WorkspaceSkillStore.Installed,
        packageFiles: Map<String, ByteArray>,
    ) {
        val snapshot = WorkspaceSkillCatalog.snapshot(current.skill, packageFiles)
        require(snapshot == current.snapshot) {
            "Skill candidate source package does not match the installed immutable version"
        }
    }

    private fun localDerivedProvenance(
        base: WorkspaceSkillContract.Provenance,
    ): WorkspaceSkillContract.Provenance =
        WorkspaceSkillContract.Provenance(
            origin = WorkspaceSkillContract.Origin.LOCAL_DERIVED,
            sourceUrl = base.sourceUrl,
            pinnedRevision = base.pinnedRevision,
        )

    private fun replaceDescription(
        original: String,
        description: String,
    ): String {
        val normalized = original.lines().joinToString(10.toChar().toString())
        val lines = normalized.lines().toMutableList()
        require(lines.firstOrNull()?.trim() == "---") {
            "Installed SKILL.md frontmatter is unavailable"
        }
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            ?: throw IllegalArgumentException("Installed SKILL.md frontmatter is not closed")
        val start = (1 until end).firstOrNull {
            !lines[it].firstOrNull().let { c -> c != null && c.isWhitespace() } &&
                lines[it].substringBefore(':', "").trim() == "description"
        } ?: throw IllegalArgumentException("Installed SKILL.md description is unavailable")

        var after = start + 1
        val raw = lines[start].substringAfter(':', "").trim()
        if (raw == "|" || raw == ">") {
            while (after < end &&
                (lines[after].isBlank() ||
                    lines[after].firstOrNull()?.isWhitespace() == true)) {
                after++
            }
        }

        val replacement = listOf(
            "description: >",
            "  $description",
        )
        val rebuilt = buildList {
            addAll(lines.subList(0, start))
            addAll(replacement)
            addAll(lines.subList(after, lines.size))
        }
        return rebuilt.joinToString("
")
    }

    private fun appendExamples(skillMd: String, examples: List<String>): String {
        if (examples.isEmpty()) return skillMd
        require(!skillMd.contains(LEARNED_HEADING)) {
            "Base skill already contains the reserved LYRA learned-examples section"
        }
        return buildString {
            append(skillMd.trimEnd())
            append("\n\n")
            appendLine(LEARNED_HEADING)
            appendLine()
            appendLine(
                "These examples were promoted from local verified evidence. They are guidance only; " +
                    "they do not expand tools, permissions, network access, source sharing, or memory access.")
            examples.forEachIndexed { index, example ->
                appendLine()
                appendLine("### Example ${index + 1}")
                appendLine(example)
            }
        }.trimEnd() + "\n"
    }

    fun materialize(
        current: WorkspaceSkillStore.Installed,
        packageFiles: Map<String, ByteArray>,
        overlay: WorkspaceSkillOverlay.Overlay,
        evidence: Collection<WorkspaceSkillImprovementEvidence.Record>,
    ): Candidate {
        currentPackageMatches(current, packageFiles)
        val promotion = WorkspaceSkillOverlayPromotion.evaluate(
            current.skill, overlay, evidence)
        val view = promotion.effectiveView

        var nextMd = current.skill.originalSkillMd
        if (view.description != current.skill.description) {
            nextMd = replaceDescription(nextMd, view.description)
        }
        nextMd = appendExamples(nextMd, view.examples)

        require(nextMd != current.skill.originalSkillMd) {
            "Skill overlay does not produce a new immutable candidate"
        }

        val nextFiles = linkedMapOf<String, ByteArray>()
        packageFiles.toSortedMap().forEach { (path, bytes) ->
            nextFiles[path] = if (path == "SKILL.md") nextMd.toByteArray(Charsets.UTF_8)
                else bytes.copyOf()
        }

        val candidate = WorkspaceSkillContract.parse(
            skillMd = nextMd,
            skillJson = current.skill.originalSkillJson,
            provenance = localDerivedProvenance(current.skill.provenance),
            packagePaths = nextFiles.keys,
        )
        WorkspaceSkillUpdate.requireNonWidening(current.skill, candidate)

        val oldManifest = current.skill.originalSkillJson?.toByteArray(Charsets.UTF_8)
        val newManifest = nextFiles["skill.json"]
        require(
            (oldManifest == null && newManifest == null) ||
                (oldManifest != null && newManifest != null &&
                    oldManifest.contentEquals(newManifest))
        ) {
            "Skill candidate materializer changed skill.json bytes"
        }

        packageFiles.forEach { (path, bytes) ->
            if (path != "SKILL.md") {
                require(nextFiles[path]?.contentEquals(bytes) == true) {
                    "Skill candidate materializer changed immutable package asset: $path"
                }
            }
        }
        return Candidate(
            skill = candidate,
            packageFiles = nextFiles.mapValues { it.value.copyOf() },
            promotion = promotion,
        )
    }
}
