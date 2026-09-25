package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillUpdateTest {
    @get:Rule val temp = TemporaryFolder()

    private fun skill(
        description: String,
        manifest: String =
            """{"allowedTools":["read_file"],"sourceSharing":"BOUNDED","memoryAccess":"READ"}""",
    ) = WorkspaceSkillContract.parse(
        """---
name: review-code
description: @@description
---
## Verification
Require deterministic evidence.
""".replace("@@description", description),
        manifest,
        packagePaths = listOf("SKILL.md", "skill.json"),
    )

    private fun files(skill: WorkspaceSkillContract.ParsedSkill) = linkedMapOf(
        "SKILL.md" to skill.originalSkillMd.toByteArray(),
        "skill.json" to requireNotNull(skill.originalSkillJson).toByteArray(),
    )

    private fun installed(
        store: WorkspaceSkillStore,
        skill: WorkspaceSkillContract.ParsedSkill,
    ): WorkspaceSkillStore.Installed {
        val bytes = files(skill)
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, bytes)
        val approval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        return store.install(skill, bytes, approval, approval.approvalToken, 1L)
    }

    private fun promotion(skill: WorkspaceSkillContract.ParsedSkill):
        WorkspaceSkillOverlayPromotion.Promotion {
        val ref = "verify:review-code:1"
        val overlay = WorkspaceSkillOverlay.Overlay(
            baseContentSha256 = skill.contentSha256,
            descriptionOverride = "Improved review guidance.",
            evidenceRefs = listOf(ref),
            createdAtMs = 2L,
        )
        val evidence = WorkspaceSkillImprovementEvidence.Record(
            ref = ref,
            skillName = skill.name,
            baseContentSha256 = skill.contentSha256,
            kind = WorkspaceSkillImprovementEvidence.Kind.DETERMINISTIC_VERIFICATION,
            signal = WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
            capturedAtMs = 2L,
            sourceRevision = "r1",
        )
        return WorkspaceSkillOverlayPromotion.evaluate(skill, overlay, listOf(evidence))
    }

    @Test fun nonWideningCandidateGetsApprovalBoundToOldNewAndEvidence() {
        val store = WorkspaceSkillStore(temp.newFolder("update-contract"))
        val old = skill("Review safely.")
        val current = installed(store, old)
        val candidate = skill(
            "Review more precisely.",
            """{"allowedTools":[],"sourceSharing":"NONE","memoryAccess":"NONE"}""",
        )
        val snapshot = WorkspaceSkillCatalog.snapshot(candidate, files(candidate))
        val promo = promotion(old)
        val request = WorkspaceSkillUpdate.request(
            current, candidate, snapshot, promo)

        assertEquals(old.contentSha256, request.fromContentSha256)
        assertEquals(candidate.contentSha256, request.toContentSha256)
        assertEquals(64, request.evidenceSha256.length)
        assertTrue(request.approvalToken.startsWith("lyra-skill-update-v1:"))

        val entry = WorkspaceSkillUpdate.updatedEntry(
            current, candidate, snapshot, promo,
            request, request.approvalToken, 10L)
        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED, entry.state)
        assertNull(entry.enabledAtMs)
        assertEquals(candidate.contentSha256, entry.contentSha256)
    }

    @Test fun permissionWideningFailsClosed() {
        val store = WorkspaceSkillStore(temp.newFolder("widen"))
        val old = skill(
            "Review safely.",
            """{"allowedTools":["read_file"],"sourceSharing":"NONE","memoryAccess":"NONE"}""",
        )
        val current = installed(store, old)
        val candidate = skill(
            "Now broader.",
            """{"allowedTools":["read_file","write_file"],"sourceSharing":"BOUNDED","memoryAccess":"READ","modelInvocable":true}""",
        )
        val snapshot = WorkspaceSkillCatalog.snapshot(candidate, files(candidate))
        assertTrue(runCatching {
            WorkspaceSkillUpdate.request(
                current, candidate, snapshot, promotion(old))
        }.isFailure)
    }

    @Test fun stalePromotionAndWrongApprovalFailClosed() {
        val store = WorkspaceSkillStore(temp.newFolder("approval"))
        val old = skill("Review safely.")
        val current = installed(store, old)
        val candidate = skill("Improved.")
        val snapshot = WorkspaceSkillCatalog.snapshot(candidate, files(candidate))

        val otherBase = skill("Other base.")
        assertTrue(runCatching {
            WorkspaceSkillUpdate.request(
                current, candidate, snapshot, promotion(otherBase))
        }.isFailure)

        val promo = promotion(old)
        val request = WorkspaceSkillUpdate.request(
            current, candidate, snapshot, promo)
        assertTrue(runCatching {
            WorkspaceSkillUpdate.updatedEntry(
                current, candidate, snapshot, promo,
                request, "wrong-token", 9L)
        }.isFailure)
    }

    @Test fun identicalCandidateIsNotAnUpdate() {
        val store = WorkspaceSkillStore(temp.newFolder("same"))
        val old = skill("Review safely.")
        val current = installed(store, old)
        val snapshot = WorkspaceSkillCatalog.snapshot(old, files(old))
        assertTrue(runCatching {
            WorkspaceSkillUpdate.request(
                current, old, snapshot, promotion(old))
        }.isFailure)
    }

    @Test fun newlyGrantedInvocationAndNestingArePermissionWidening() {
        val store = WorkspaceSkillStore(temp.newFolder("invocation"))
        val old = skill(
            "Old.",
            """{"allowedTools":[],"userInvocable":false,"modelInvocable":false,"maxNestingDepth":0}""",
        )
        val current = installed(store, old)

        val user = skill(
            "User broader.",
            """{"allowedTools":[],"userInvocable":true,"modelInvocable":false,"maxNestingDepth":0}""",
        )
        assertTrue(runCatching {
            WorkspaceSkillUpdate.request(
                current, user, WorkspaceSkillCatalog.snapshot(user, files(user)), promotion(old))
        }.isFailure)

        val nesting = skill(
            "Nested broader.",
            """{"allowedTools":[],"userInvocable":false,"modelInvocable":false,"maxNestingDepth":1}""",
        )
        assertTrue(runCatching {
            WorkspaceSkillUpdate.request(
                current, nesting, WorkspaceSkillCatalog.snapshot(nesting, files(nesting)), promotion(old))
        }.isFailure)
    }
}
