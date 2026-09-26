package com.myra.assistant.ui.workspace

/**
 * Bounded local evidence records for skill improvement.
 *
 * Records carry only references/hashes/outcomes. They never store project source, provider prompts,
 * model chain-of-thought, secrets or permission payloads.
 */
internal object WorkspaceSkillImprovementEvidence {
    enum class Kind {
        DETERMINISTIC_VERIFICATION,
        VERIFIED_RECOVERY,
        USER_CONFIRMED,
        USER_UNDO,
    }

    enum class Signal {
        SUPPORTS_IMPROVEMENT,
        COUNTER_EVIDENCE,
    }

    data class Record(
        val ref: String,
        val skillName: String,
        val baseContentSha256: String,
        val kind: Kind,
        val signal: Signal,
        val capturedAtMs: Long,
        val sourceRevision: String? = null,
    )

    private val ref = Regex("""[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}""")
    private val name = Regex("""[a-z0-9][a-z0-9-]{0,63}""")
    private val sha256 = Regex("""[0-9a-f]{64}""")
    private val revision = Regex("""[A-Za-z0-9][A-Za-z0-9._:-]{0,127}""")

    fun validate(record: Record): Record {
        require(ref.matches(record.ref)) { "Skill improvement evidence ref is invalid" }
        require(name.matches(record.skillName)) { "Skill improvement evidence skill name is invalid" }
        require(sha256.matches(record.baseContentSha256)) {
            "Skill improvement evidence skill hash is invalid"
        }
        require(record.capturedAtMs >= 0L) { "Skill improvement evidence timestamp is invalid" }
        record.sourceRevision?.let {
            require(revision.matches(it)) { "Skill improvement evidence source revision is invalid" }
        }
        return record
    }
}
