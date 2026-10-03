package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillUpdateCandidateTest {
    @get:Rule val temp = TemporaryFolder()

    private fun skill(
        provenance: WorkspaceSkillContract.Provenance =
            WorkspaceSkillContract.Provenance(WorkspaceSkillContract.Origin.USER_SUPPLIED),
    ): WorkspaceSkillContract.ParsedSkill = WorkspaceSkillContract.parse(
        """---
name: review-code
description: Review code safely.
license: MIT
---
## Verification
Require deterministic evidence.
""",
        """{"allowedTools":["read_file"],"sourceSharing":"BOUNDED"}""",
        provenance = provenance,
        packagePaths = listOf(
            "SKILL.md", "skill.json", "references/checklist.md"),
    )

    private fun files(skill: WorkspaceSkillContract.ParsedSkill) = linkedMapOf(
        "SKILL.md" to skill.originalSkillMd.toByteArray(),
        "skill.json" to requireNotNull(skill.originalSkillJson).toByteArray(),
        "references/checklist.md" to "Keep this exact asset.".toByteArray(),
    )

    private fun install(
        store: WorkspaceSkillStore,
        skill: WorkspaceSkillContract.ParsedSkill,
    ): WorkspaceSkillStore.Installed {
        val packageFiles = files(skill)
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, packageFiles)
        val approval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        return store.install(
            skill, packageFiles, approval, approval.approvalToken, 1L)
    }

    private fun evidence(
        skill: WorkspaceSkillContract.ParsedSkill,
        ref: String,
    ) = WorkspaceSkillImprovementEvidence.Record(
        ref = ref,
        skillName = skill.name,
        baseContentSha256 = skill.contentSha256,
        kind = WorkspaceSkillImprovementEvidence.Kind.DETERMINISTIC_VERIFICATION,
        signal = WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
        capturedAtMs = 2L,
        sourceRevision = "source-1",
    )

    @Test fun overlayBecomesLocalDerivedCandidateWithoutChangingManifestOrAssets() {
        val store = WorkspaceSkillStore(temp.newFolder("candidate"))
        val base = skill()
        val installed = install(store, base)
        val packageFiles = files(base)
        val ref = "verify:review-code:candidate"
        val overlay = WorkspaceSkillOverlay.Overlay(
            baseContentSha256 = base.contentSha256,
            descriptionOverride = "Review one bounded change using current evidence.",
            examples = listOf("Check the current source revision before proposing edits."),
            evidenceRefs = listOf(ref),
            createdAtMs = 3L,
        )

        val candidate = WorkspaceSkillUpdateCandidate.materialize(
            installed, packageFiles, overlay, listOf(evidence(base, ref)))

        assertEquals(WorkspaceSkillContract.Origin.LOCAL_DERIVED,
            candidate.skill.provenance.origin)
        assertEquals("Review one bounded change using current evidence.",
            candidate.skill.description)
        assertTrue(candidate.skill.body.contains("## LYRA Learned Examples"))
        assertTrue(candidate.skill.body.contains("Check the current source revision"))
        assertArrayEquals(packageFiles["skill.json"], candidate.packageFiles["skill.json"])
        assertArrayEquals(packageFiles["references/checklist.md"],
            candidate.packageFiles["references/checklist.md"])
        assertNotEquals(base.contentSha256, candidate.skill.contentSha256)
        WorkspaceSkillUpdate.requireNonWidening(base, candidate.skill)
    }

    @Test fun githubAncestryIsPreservedButCandidateIsNotClaimedPinned() {
        val upstream = WorkspaceSkillContract.Provenance(
            WorkspaceSkillContract.Origin.GITHUB_PINNED,
            "https://github.com/example/skills/blob/1234567890abcdef1234567890abcdef12345678/SKILL.md",
            "1234567890abcdef1234567890abcdef12345678",
        )
        val store = WorkspaceSkillStore(temp.newFolder("candidate-github"))
        val base = skill(upstream)
        val installed = install(store, base)
        val ref = "verify:review-code:github"
        val overlay = WorkspaceSkillOverlay.Overlay(
            baseContentSha256 = base.contentSha256,
            descriptionOverride = "Locally improved review guidance.",
            evidenceRefs = listOf(ref),
            createdAtMs = 3L,
        )

        val candidate = WorkspaceSkillUpdateCandidate.materialize(
            installed, files(base), overlay, listOf(evidence(base, ref)))

        assertEquals(WorkspaceSkillContract.Origin.LOCAL_DERIVED,
            candidate.skill.provenance.origin)
        assertEquals(upstream.sourceUrl, candidate.skill.provenance.sourceUrl)
        assertEquals(upstream.pinnedRevision, candidate.skill.provenance.pinnedRevision)
    }

    @Test fun descriptionOnlyAndExamplesOnlyBothProduceBoundedCandidates() {
        val store = WorkspaceSkillStore(temp.newFolder("candidate-modes"))
        val base = skill()
        val installed = install(store, base)

        val descRef = "verify:review-code:desc"
        val desc = WorkspaceSkillUpdateCandidate.materialize(
            installed,
            files(base),
            WorkspaceSkillOverlay.Overlay(
                baseContentSha256 = base.contentSha256,
                descriptionOverride = "Sharper review guidance.",
                evidenceRefs = listOf(descRef),
                createdAtMs = 3L,
            ),
            listOf(evidence(base, descRef)),
        )
        assertEquals("Sharper review guidance.", desc.skill.description)

        val exampleRef = "verify:review-code:example"
        val example = WorkspaceSkillUpdateCandidate.materialize(
            installed,
            files(base),
            WorkspaceSkillOverlay.Overlay(
                baseContentSha256 = base.contentSha256,
                examples = listOf("Verify the exact changed file."),
                evidenceRefs = listOf(exampleRef),
                createdAtMs = 4L,
            ),
            listOf(evidence(base, exampleRef)),
        )
        assertEquals(base.description, example.skill.description)
        assertTrue(example.skill.body.contains("Verify the exact changed file."))
    }

    @Test fun stalePackageOrNoEffectiveChangeFailsClosed() {
        val store = WorkspaceSkillStore(temp.newFolder("candidate-fail"))
        val base = skill()
        val installed = install(store, base)
        val ref = "verify:review-code:nochange"
        val same = WorkspaceSkillOverlay.Overlay(
            baseContentSha256 = base.contentSha256,
            descriptionOverride = base.description,
            evidenceRefs = listOf(ref),
            createdAtMs = 3L,
        )
        assertTrue(runCatching {
            WorkspaceSkillUpdateCandidate.materialize(
                installed, files(base), same, listOf(evidence(base, ref)))
        }.isFailure)

        val tampered = files(base).toMutableMap().also {
            it["references/checklist.md"] = "tampered".toByteArray()
        }
        val changed = same.copy(descriptionOverride = "Changed.")
        assertTrue(runCatching {
            WorkspaceSkillUpdateCandidate.materialize(
                installed, tampered, changed, listOf(evidence(base, ref)))
        }.isFailure)
    }

    @Test fun localDerivedProvenanceRequiresCompleteValidAncestryPair() {
        val derivedBad = WorkspaceSkillContract.Provenance(
            WorkspaceSkillContract.Origin.LOCAL_DERIVED,
            sourceUrl = "https://github.com/a/b",
            pinnedRevision = null,
        )
        assertTrue(runCatching {
            WorkspaceSkillContract.parse(
                """---
name: test-skill
description: test
---
## Verification
Check.
""",
                provenance = derivedBad,
            )
        }.isFailure)
    }
}
