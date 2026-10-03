package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceSkillOverlayPromotionTest {
    private fun skill() = WorkspaceSkillContract.parse(
        """---
name: review-code
description: Review code safely.
---
## Verification
Require deterministic checks.
"""
    )

    private fun overlay(skill: WorkspaceSkillContract.ParsedSkill, refs: List<String>) =
        WorkspaceSkillOverlay.Overlay(
            baseContentSha256 = skill.contentSha256,
            descriptionOverride = "Review one bounded change using current evidence.",
            examples = listOf("Check current source revision before suggesting a change."),
            evidenceRefs = refs,
            createdAtMs = 20L,
        )

    private fun record(
        skill: WorkspaceSkillContract.ParsedSkill,
        ref: String = "eval:review-code:001",
        kind: WorkspaceSkillImprovementEvidence.Kind =
            WorkspaceSkillImprovementEvidence.Kind.DETERMINISTIC_VERIFICATION,
        signal: WorkspaceSkillImprovementEvidence.Signal =
            WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
    ) = WorkspaceSkillImprovementEvidence.Record(
        ref = ref,
        skillName = skill.name,
        baseContentSha256 = skill.contentSha256,
        kind = kind,
        signal = signal,
        capturedAtMs = 10L,
        sourceRevision = "source-1",
    )

    @Test fun matchingLocalEvidenceMakesOverlayPromotableWithoutChangingBaseSkill() {
        val skill = skill()
        val original = skill.originalSkillMd
        val promotion = WorkspaceSkillOverlayPromotion.evaluate(
            skill,
            overlay(skill, listOf("eval:review-code:001")),
            listOf(record(skill)),
        )
        assertEquals(
            WorkspaceSkillOverlayPromotion.Status.PROMOTABLE_OVERLAY,
            promotion.status,
        )
        assertEquals(64, promotion.evidenceSha256.length)
        assertEquals("Review one bounded change using current evidence.",
            promotion.effectiveView.description)
        assertEquals(original, skill.originalSkillMd)
    }

    @Test fun missingEvidenceAndModelOnlyStyleClaimsCannotPromote() {
        val skill = skill()
        assertTrue(runCatching {
            WorkspaceSkillOverlayPromotion.evaluate(
                skill,
                overlay(skill, listOf("eval:review-code:missing")),
                emptyList(),
            )
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceSkillOverlayPromotion.evaluate(
                skill,
                overlay(skill, emptyList()),
                listOf(record(skill)),
            )
        }.isFailure)
    }

    @Test fun staleSkillHashFailsClosed() {
        val skill = skill()
        val stale = record(skill).copy(baseContentSha256 = "0".repeat(64))
        assertTrue(runCatching {
            WorkspaceSkillOverlayPromotion.evaluate(
                skill,
                overlay(skill, listOf(stale.ref)),
                listOf(stale),
            )
        }.isFailure)
    }

    @Test fun counterEvidenceBlocksPromotion() {
        val skill = skill()
        val good = record(skill, "eval:review-code:good")
        val bad = record(
            skill,
            ref = "eval:review-code:undo",
            kind = WorkspaceSkillImprovementEvidence.Kind.USER_UNDO,
            signal = WorkspaceSkillImprovementEvidence.Signal.COUNTER_EVIDENCE,
        )
        assertTrue(runCatching {
            WorkspaceSkillOverlayPromotion.evaluate(
                skill,
                overlay(skill, listOf(good.ref, bad.ref)),
                listOf(good, bad),
            )
        }.isFailure)
    }

    @Test fun userUndoCannotBeMisrepresentedAsSupportingEvidence() {
        val skill = skill()
        val undo = record(
            skill,
            kind = WorkspaceSkillImprovementEvidence.Kind.USER_UNDO,
            signal = WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
        )
        assertTrue(runCatching {
            WorkspaceSkillOverlayPromotion.evaluate(
                skill,
                overlay(skill, listOf(undo.ref)),
                listOf(undo),
            )
        }.isFailure)
    }
}
