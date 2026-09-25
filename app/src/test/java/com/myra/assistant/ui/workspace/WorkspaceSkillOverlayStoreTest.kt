package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSkillOverlayStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun skill(description: String = "Review code safely.") =
        WorkspaceSkillContract.parse(
            """---
name: review-code
description: $description
---
## Verification
Require deterministic checks.
"""
        )

    private fun evidence(
        skill: WorkspaceSkillContract.ParsedSkill,
        ref: String = "eval:review-code:001",
        signal: WorkspaceSkillImprovementEvidence.Signal =
            WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
    ) = WorkspaceSkillImprovementEvidence.Record(
        ref = ref,
        skillName = skill.name,
        baseContentSha256 = skill.contentSha256,
        kind = WorkspaceSkillImprovementEvidence.Kind.DETERMINISTIC_VERIFICATION,
        signal = signal,
        capturedAtMs = 10L,
        sourceRevision = "source-1",
    )

    private fun overlay(
        skill: WorkspaceSkillContract.ParsedSkill,
        refs: List<String> = listOf("eval:review-code:001"),
    ) = WorkspaceSkillOverlay.Overlay(
        baseContentSha256 = skill.contentSha256,
        descriptionOverride = "Review one bounded change using evidence.",
        examples = listOf("Check current source revision before review."),
        evidenceRefs = refs,
        createdAtMs = 20L,
    )

    @Test fun promotedOverlayPersistsAsSidecarAndReopensWithEvidence() {
        val root = temp.newFolder("skills")
        val skill = skill()
        val store = WorkspaceSkillOverlayStore(root)
        val saved = store.save(
            skill, overlay(skill), listOf(evidence(skill)), savedAtMs = 30L)

        assertEquals(skill.name, saved.skillName)
        assertEquals(64, saved.evidenceSha256.length)
        val reopened = WorkspaceSkillOverlayStore(root).load(skill)
        assertNotNull(reopened)
        assertEquals(saved.overlay, reopened?.overlay)
        assertEquals(saved.evidenceSha256, reopened?.evidenceSha256)
        assertEquals(listOf("eval:review-code:001"),
            reopened?.evidence?.map { it.ref })
    }

    @Test fun originalSkillAndOtherPackageFilesStayUntouched() {
        val root = temp.newFolder("immutable")
        val packageSentinel = File(root, "packages/sentinel.txt")
        packageSentinel.parentFile.mkdirs()
        packageSentinel.writeText("IMMUTABLE")
        val skill = skill()
        val original = skill.originalSkillMd

        WorkspaceSkillOverlayStore(root).save(
            skill, overlay(skill), listOf(evidence(skill)), savedAtMs = 30L)

        assertEquals("IMMUTABLE", packageSentinel.readText())
        assertEquals(original, skill.originalSkillMd)
        assertTrue(File(root, "overlays/review-code.json").isFile)
    }

    @Test fun staleSkillContentHashFailsClosedOnLoad() {
        val root = temp.newFolder("stale")
        val first = skill()
        val store = WorkspaceSkillOverlayStore(root)
        store.save(first, overlay(first), listOf(evidence(first)), savedAtMs = 30L)

        val changed = skill("Different immutable skill content.")
        assertTrue(runCatching { store.load(changed) }.isFailure)
    }

    @Test fun tamperedEvidenceDigestFailsClosed() {
        val root = temp.newFolder("tampered")
        val skill = skill()
        val store = WorkspaceSkillOverlayStore(root)
        store.save(skill, overlay(skill), listOf(evidence(skill)), savedAtMs = 30L)

        val file = File(root, "overlays/review-code.json")
        val json = JSONObject(file.readText())
        json.put("evidenceSha256", "0".repeat(64))
        file.writeText(json.toString())

        assertTrue(runCatching { store.load(skill) }.isFailure)
    }

    @Test fun counterEvidenceCannotBePersistedAsPromotedOverlay() {
        val root = temp.newFolder("counter")
        val skill = skill()
        val bad = evidence(
            skill,
            signal = WorkspaceSkillImprovementEvidence.Signal.COUNTER_EVIDENCE,
        )
        assertTrue(runCatching {
            WorkspaceSkillOverlayStore(root).save(
                skill, overlay(skill), listOf(bad), savedAtMs = 30L)
        }.isFailure)
        assertFalse(File(root, "overlays/review-code.json").exists())
    }

    @Test fun clearRemovesOnlySidecar() {
        val root = temp.newFolder("clear")
        val sentinel = File(root, "packages/keep.txt")
        sentinel.parentFile.mkdirs()
        sentinel.writeText("KEEP")
        val skill = skill()
        val store = WorkspaceSkillOverlayStore(root)
        store.save(skill, overlay(skill), listOf(evidence(skill)), savedAtMs = 30L)
        store.clear(skill.name)

        assertNull(store.load(skill))
        assertEquals("KEEP", sentinel.readText())
    }
}
