package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSkillRetentionAuditTest {
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
        return store.install(
            skill, packageFiles, approval, approval.approvalToken, at)
    }

    private fun update(
        store: WorkspaceSkillStore,
        current: WorkspaceSkillStore.Installed,
        next: WorkspaceSkillContract.ParsedSkill,
        at: Long,
    ): WorkspaceSkillStore.Installed {
        val packageFiles = files(next)
        val snapshot = WorkspaceSkillCatalog.snapshot(next, packageFiles)
        val permissionSha256 =
            WorkspaceSkillCatalog.approvalRequest(next, snapshot).permissionSha256
        val candidate = WorkspaceSkillUpdatePreview.Candidate(
            skill = next,
            snapshot = snapshot,
            permissionSha256 = permissionSha256,
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

    @Test fun auditProtectsCurrentAndRollbackAndExplicitCleanupDeletesOnlyOrphans() {
        val root = temp.newFolder("retention-audit")
        val store = WorkspaceSkillStore(root)
        val first = install(store, skill("V1."), 1L)
        val second = update(store, first, skill("V2."), 2L)

        val orphanHash = "f".repeat(64)
        val orphan = File(root, "packages/" + orphanHash)
        assertTrue(orphan.mkdirs())
        File(orphan, "legacy").writeText("old unreferenced package")

        val ignored = File(root, "packages/legacy-note")
        ignored.writeText("not a package directory")

        val audit = store.retentionAudit()
        assertEquals(listOf(second.entry.packageSha256), audit.currentPackageSha256)
        assertEquals(listOf(first.entry.packageSha256), audit.rollbackPackageSha256)
        assertEquals(listOf(orphanHash), audit.reclaimablePackageSha256)
        assertEquals(1, audit.ignoredEntryCount)

        val deleted = store.cleanupAuditedPackages(audit)
        assertEquals(listOf(orphanHash), deleted)
        assertFalse(orphan.exists())
        assertTrue(ignored.isFile)
        assertTrue(File(root, "packages/" + first.entry.packageSha256).isDirectory)
        assertTrue(File(root, "packages/" + second.entry.packageSha256).isDirectory)
        assertEquals(second.entry.packageSha256, store.load("review-code").entry.packageSha256)
        assertEquals(first.entry.packageSha256, store.loadRollback("review-code").entry.packageSha256)
    }

    @Test fun staleAuditFailsClosedWithoutDeletingPreviouslyReviewedPackages() {
        val root = temp.newFolder("retention-stale")
        val store = WorkspaceSkillStore(root)
        install(store, skill("V1."), 1L)

        val firstHash = "e".repeat(64)
        val firstOrphan = File(root, "packages/" + firstHash)
        assertTrue(firstOrphan.mkdirs())
        File(firstOrphan, "legacy").writeText("first")
        val reviewed = store.retentionAudit()

        val secondHash = "d".repeat(64)
        val secondOrphan = File(root, "packages/" + secondHash)
        assertTrue(secondOrphan.mkdirs())
        File(secondOrphan, "legacy").writeText("second")

        assertTrue(runCatching {
            store.cleanupAuditedPackages(reviewed)
        }.isFailure)
        assertTrue(firstOrphan.isDirectory)
        assertTrue(secondOrphan.isDirectory)

        val fresh = store.retentionAudit()
        assertEquals(listOf(secondHash, firstHash).sorted(), fresh.reclaimablePackageSha256)
    }
}
