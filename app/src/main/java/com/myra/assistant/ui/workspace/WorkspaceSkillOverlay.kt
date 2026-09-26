package com.myra.assistant.ui.workspace

/**
 * Learned skill improvements are sidecars. They never mutate the immutable imported SKILL.md bytes.
 */
internal object WorkspaceSkillOverlay {
    private const val MAX_DESCRIPTION_CHARS = 1_024
    private const val MAX_EXAMPLES = 8
    private const val MAX_EXAMPLE_CHARS = 2_000
    private const val MAX_EVIDENCE_REFS = 32

    data class Overlay(
        val baseContentSha256: String,
        val descriptionOverride: String? = null,
        val examples: List<String> = emptyList(),
        val evidenceRefs: List<String> = emptyList(),
        val createdAtMs: Long,
    )

    data class EffectiveView(
        val description: String,
        val examples: List<String>,
        val baseContentSha256: String,
    )

    private val sha256 = Regex("""[0-9a-f]{64}""")
    private val evidenceRef = Regex("""[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}""")

    fun validate(
        skill: WorkspaceSkillContract.ParsedSkill,
        overlay: Overlay,
    ): EffectiveView {
        require(sha256.matches(overlay.baseContentSha256) &&
            overlay.baseContentSha256 == skill.contentSha256) {
            "Skill overlay does not match the immutable skill content hash"
        }
        require(overlay.createdAtMs >= 0L) { "Skill overlay timestamp is invalid" }
        val description = overlay.descriptionOverride?.trim()?.also {
            require(it.isNotBlank() && it.length <= MAX_DESCRIPTION_CHARS &&
                it.none(Char::isISOControl)) {
                "Skill overlay description is invalid"
            }
        } ?: skill.description
        require(overlay.examples.size <= MAX_EXAMPLES) { "Too many skill overlay examples" }
        val examples = overlay.examples.map { example ->
            example.trim().also {
                require(it.isNotBlank() && it.length <= MAX_EXAMPLE_CHARS) {
                    "Skill overlay example is empty or too large"
                }
                require(!WorkspaceSourceContext.containsPossibleSecret(it)) {
                    "Possible secret detected in skill overlay example"
                }
            }
        }
        require(overlay.evidenceRefs.size <= MAX_EVIDENCE_REFS &&
            overlay.evidenceRefs.all { evidenceRef.matches(it) }) {
            "Skill overlay evidence references are invalid"
        }
        return EffectiveView(
            description = description,
            examples = examples,
            baseContentSha256 = overlay.baseContentSha256,
        )
    }
}
