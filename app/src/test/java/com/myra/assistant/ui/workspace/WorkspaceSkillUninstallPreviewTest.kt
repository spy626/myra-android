package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillUninstallPreviewTest {
    @get:Rule val temp = TemporaryFolder()

    private fun skill(description: String): WorkspaceSkillContract.ParsedSkill =
        WorkspaceSkillContract.parse(
            """---
name: review-code
description: $description
---
## Verification
Check exact local evidence.
""",
            """{"allowedTools":[]}""",
            packagePaths = listOf("SKILL.md", "skill.json"),
        )

    private fun files(skill: WorkspaceSkillContract.ParsedSkill) = linkedMapOf(
        "SKILL.md" to skill.originalSkillMd.toByteArray(),
        "skill.json" to requireNotNull(skill.originalSkillJson).toByteArray(),
    )

    private fun install(
        store: WorkspaceSkillStore,
        skill: WorkspaceSkillContract.ParsedSkill,
        at: Long,
    ): WorkspaceSkillStore.Installed {
        val packageFiles = files(skill)
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, packageFiles)
        val approval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        return store.install(skill, packageFiles, approval, approval.approvalToken, at)
    }

    private fun update(
        store: WorkspaceSkillStore,
        current: WorkspaceSkillStore.Installed,
        next: WorkspaceSkillContract.ParsedSkill,
        at: Long,
    ): WorkspaceSkillStore.Installed {
        val packageFiles = files(next)
        val snapshot = WorkspaceSkillCatalog.snapshot(next, packageFiles)
        val candidate = WorkspaceSkillUpdatePreview.Candidate(
            skill = next,
            snapshot = snapshot,
            permissionSha256 =
                WorkspaceSkillCatalog.approvalRequest(next, snapshot).permissionSha256,
            packageFiles = packageFiles.mapValues { it.value.copyOf() },
        )
        val prepared = WorkspaceSkillApprovedUpdate.prepare(current, candidate)
        return store.updateApprovedPackage(
            current.skill.name,
            candidate,
            prepared.request,
            prepared.request.approvalToken,
            at,
        )
    }

    @Test fun previewFreshInstallShowsExactCurrentIdentityAndDoesNotMutate() {
        val store = WorkspaceSkillStore(temp.newFolder("uninstall-current"))
        val current = install(store, skill("Only."), 1L)
        val before = store.readCatalog()

        val preview = WorkspaceSkillUninstallPreview.from(current, null)

        assertEquals(before, store.readCatalog())
        assertEquals("review-code", preview.name)
        assertEquals("UNINSTALL PREVIEW", preview.status)
        assertTrue(preview.summary.contains("No approval token"))
        assertTrue(preview.rows.any {
            it.label == "Package SHA-256" && it.value == current.entry.packageSha256
        })
        assertTrue(preview.rows.any {
            it.label == "Rollback impact" && it.value.contains("No rollback point")
        })
    }

    @Test fun previewWithRollbackRequiresExactVerifiedRollbackAndRemainsReadOnly() {
        val store = WorkspaceSkillStore(temp.newFolder("uninstall-rollback"))
        val first = install(store, skill("V1."), 1L)
        val current = update(store, first, skill("V2."), 2L)
        val rollback = store.loadRollback("review-code")
        val before = store.readCatalog()

        val preview = WorkspaceSkillUninstallPreview.from(current, rollback)

        assertEquals(before, store.readCatalog())
        assertTrue(preview.rows.any {
            it.label == "Rollback package SHA-256" &&
                it.value == first.entry.packageSha256
        })
        assertTrue(preview.rows.any {
            it.label == "Package retention impact" &&
                it.value.contains("both current and rollback")
        })

        val wrong = current.copy(entry = current.entry.copy(rollbackPoint = null))
        assertTrue(runCatching {
            WorkspaceSkillUninstallPreview.from(wrong, rollback)
        }.isFailure)
    }
}
