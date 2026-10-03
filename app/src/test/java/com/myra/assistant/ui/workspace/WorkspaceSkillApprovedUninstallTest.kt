package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSkillApprovedUninstallTest {
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
        at: Long = 1L,
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
        at: Long = 2L,
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

    @Test fun approvedEnabledUninstallRemovesOnlyCatalogAuthorityAndRetainsBothPackages() {
        val root = temp.newFolder("approved-uninstall")
        val store = WorkspaceSkillStore(root)
        val first = install(store, skill("V1."))
        var current = update(store, first, skill("V2."))

        val readiness = WorkspaceSkillEnablement.test(
            current, WorkspaceSkillEnablement.Environment(), 3L)
        val enable = WorkspaceSkillEnablement.enableRequest(current, readiness)
        current = store.enable(
            "review-code",
            WorkspaceSkillEnablement.Environment(),
            enable,
            enable.approvalToken,
            4L,
        )
        val rollback = store.loadRollback("review-code")
        val prepared = WorkspaceSkillApprovedUninstall.prepare(current, rollback)

        assertEquals(WorkspaceSkillCatalog.State.ENABLED, prepared.request.state)
        assertEquals(current.entry.enableBindingSha256, prepared.request.activationBindingSha256)
        assertEquals(first.entry.packageSha256, prepared.request.rollbackPackageSha256)
        assertTrue(prepared.approvalSummary.contains("WARNING"))
        assertFalse(prepared.approvalSummary.contains(prepared.request.approvalToken))

        store.uninstallApproved(
            "review-code",
            prepared.request,
            prepared.request.approvalToken,
        )

        assertTrue(store.readCatalog().entries.isEmpty())
        assertTrue(runCatching { store.load("review-code") }.isFailure)
        assertTrue(File(root, "packages/" + current.entry.packageSha256).isDirectory)
        assertTrue(File(root, "packages/" + rollback.entry.packageSha256).isDirectory)

        val audit = store.retentionAudit()
        assertTrue(audit.currentPackageSha256.isEmpty())
        assertTrue(audit.rollbackPackageSha256.isEmpty())
        assertEquals(
            listOf(current.entry.packageSha256, rollback.entry.packageSha256).sorted(),
            audit.reclaimablePackageSha256,
        )
    }

    @Test fun wrongTokenAndStaleActivationFailClosedWithoutChangingCatalog() {
        val root = temp.newFolder("uninstall-stale")
        val store = WorkspaceSkillStore(root)
        var current = install(store, skill("Only."))
        val prepared = WorkspaceSkillApprovedUninstall.prepare(current, null)

        assertTrue(runCatching {
            store.uninstallApproved("review-code", prepared.request, "wrong-token")
        }.isFailure)
        assertEquals(current.entry, store.load("review-code").entry)

        val readiness = WorkspaceSkillEnablement.test(
            current, WorkspaceSkillEnablement.Environment(), 2L)
        val enable = WorkspaceSkillEnablement.enableRequest(current, readiness)
        current = store.enable(
            "review-code",
            WorkspaceSkillEnablement.Environment(),
            enable,
            enable.approvalToken,
            3L,
        )

        assertTrue(runCatching {
            store.uninstallApproved(
                "review-code",
                prepared.request,
                prepared.request.approvalToken,
            )
        }.isFailure)
        assertEquals(WorkspaceSkillCatalog.State.ENABLED, store.load("review-code").entry.state)
        assertEquals(current.entry.packageSha256, store.load("review-code").entry.packageSha256)
    }

    @Test fun tamperedRollbackFailsClosedBeforeCatalogRemoval() {
        val root = temp.newFolder("uninstall-rollback-tamper")
        val store = WorkspaceSkillStore(root)
        val first = install(store, skill("V1."))
        val current = update(store, first, skill("V2."))
        val rollback = store.loadRollback("review-code")
        val prepared = WorkspaceSkillApprovedUninstall.prepare(current, rollback)

        File(
            root,
            "packages/" + rollback.entry.packageSha256 + "/SKILL.md",
        ).appendText("\nTampered")

        assertTrue(runCatching {
            store.uninstallApproved(
                "review-code",
                prepared.request,
                prepared.request.approvalToken,
            )
        }.isFailure)
        assertEquals(current.entry.packageSha256, store.load("review-code").entry.packageSha256)
        assertEquals(1, store.readCatalog().entries.size)
    }
}
