package com.myra.assistant.ui.workspace

import java.security.MessageDigest

/**
 * Evidence gate for promoting a learned skill sidecar overlay.
 *
 * It never rewrites SKILL.md, enables a skill, changes permissions, or applies code. It only proves
 * that a candidate overlay is bound to the current immutable skill and supported by local evidence.
 */
internal object WorkspaceSkillOverlayPromotion {
    enum class Status { PROMOTABLE_OVERLAY }

    data class Promotion(
        val status: Status,
        val effectiveView: WorkspaceSkillOverlay.EffectiveView,
        val evidenceSha256: String,
        val evidenceRefs: List<String>,
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun evaluate(
        skill: WorkspaceSkillContract.ParsedSkill,
        overlay: WorkspaceSkillOverlay.Overlay,
        evidence: Collection<WorkspaceSkillImprovementEvidence.Record>,
    ): Promotion {
        val view = WorkspaceSkillOverlay.validate(skill, overlay)
        require(overlay.evidenceRefs.isNotEmpty()) {
            "Skill overlay promotion requires local evidence references"
        }
        require(overlay.evidenceRefs.distinct().size == overlay.evidenceRefs.size) {
            "Skill overlay evidence references must be unique"
        }

        val validated = evidence.map(WorkspaceSkillImprovementEvidence::validate)
        require(validated.map { it.ref }.distinct().size == validated.size) {
            "Duplicate skill improvement evidence record"
        }
        val byRef = validated.associateBy { it.ref }
        val selected = overlay.evidenceRefs.map { ref ->
            byRef[ref] ?: throw IllegalArgumentException(
                "Skill overlay evidence reference is missing: $ref")
        }
        require(selected.all {
            it.skillName == skill.name &&
                it.baseContentSha256 == skill.contentSha256
        }) {
            "Skill overlay evidence does not match the immutable skill"
        }
        require(selected.none {
            it.signal == WorkspaceSkillImprovementEvidence.Signal.COUNTER_EVIDENCE
        }) {
            "Counter-evidence blocks skill overlay promotion"
        }
        require(selected.any {
            it.signal == WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT &&
                it.kind in setOf(
                    WorkspaceSkillImprovementEvidence.Kind.DETERMINISTIC_VERIFICATION,
                    WorkspaceSkillImprovementEvidence.Kind.VERIFIED_RECOVERY,
                    WorkspaceSkillImprovementEvidence.Kind.USER_CONFIRMED,
                )
        }) {
            "Skill overlay needs at least one locally authoritative supporting evidence record"
        }

        val digestBody = selected.sortedBy { it.ref }.joinToString("\n") {
            listOf(
                it.ref,
                it.skillName,
                it.baseContentSha256,
                it.kind.name,
                it.signal.name,
                it.capturedAtMs.toString(),
                it.sourceRevision.orEmpty(),
            ).joinToString("|")
        }
        return Promotion(
            status = Status.PROMOTABLE_OVERLAY,
            effectiveView = view,
            evidenceSha256 = sha256(digestBody),
            evidenceRefs = overlay.evidenceRefs.toList(),
        )
    }
}
