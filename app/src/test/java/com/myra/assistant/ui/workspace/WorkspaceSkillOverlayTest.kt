package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceSkillOverlayTest {
    private fun skill() = WorkspaceSkillContract.parse(
        """---
name: review-code
description: Review code safely.
---
## Verification
Run deterministic checks.
"""
    )

    @Test fun overlayChangesEffectiveDescriptionWithoutChangingOriginalSkill() {
        val skill = skill()
        val original = skill.originalSkillMd
        val view = WorkspaceSkillOverlay.validate(
            skill,
            WorkspaceSkillOverlay.Overlay(
                baseContentSha256 = skill.contentSha256,
                descriptionOverride = "Review one bounded change using evidence.",
                examples = listOf("Check the current source revision before review."),
                evidenceRefs = listOf("eval:skill-review:001"),
                createdAtMs = 123L,
            )
        )
        assertEquals("Review one bounded change using evidence.", view.description)
        assertEquals(original, skill.originalSkillMd)
        assertEquals(skill.contentSha256, view.baseContentSha256)
    }

    @Test fun staleOverlayHashAndSecretExamplesFailClosed() {
        val skill = skill()
        assertTrue(runCatching {
            WorkspaceSkillOverlay.validate(
                skill,
                WorkspaceSkillOverlay.Overlay(
                    baseContentSha256 = "0".repeat(64),
                    createdAtMs = 1L,
                )
            )
        }.isFailure)

        assertTrue(runCatching {
            WorkspaceSkillOverlay.validate(
                skill,
                WorkspaceSkillOverlay.Overlay(
                    baseContentSha256 = skill.contentSha256,
                    examples = listOf("api_key = sk-abcdefghijklmnop"),
                    createdAtMs = 1L,
                )
            )
        }.isFailure)
    }
}
