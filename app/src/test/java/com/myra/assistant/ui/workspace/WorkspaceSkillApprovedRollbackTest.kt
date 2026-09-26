package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSkillApprovedRollbackTest {
    @get:Rule val temp = TemporaryFolder()

    private fun skill(
        description: String,
        manifest: String,
        provenance: WorkspaceSkillContract.Provenance =
            WorkspaceSkillContract.Provenance(WorkspaceSkillContract.Origin.USER_SUPPLIED),
    ): WorkspaceSkillContract.ParsedSkill =
        WorkspaceSkillContract.parse(
            """---
name: review-code
description: """ + description + """
---
## Verification
Check exact local evidence.
""",
            manifest,
            provenance = provenance,
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

    private fun candidate(
        skill: WorkspaceSkillContract.ParsedSkill,
    ): WorkspaceSkillUpdatePreview.Candidate {
        val packageFiles = files(skill)
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, packageFiles)
        return WorkspaceSkillUpdatePreview.Candidate(
            skill = skill,
            snapshot = snapshot,
            permissionSha256 =
                WorkspaceSkillCatalog.approvalRequest(skill, snapshot).permissionSha256,
            packageFiles = packageFiles.mapValues { it.value.copyOf() },
        )
    }

    private fun update(
        store: WorkspaceSkillStore,
        current: WorkspaceSkillStore.Installed,
        next: WorkspaceSkillContract.ParsedSkill,
        at: Long = 2L,
    ): WorkspaceSkillStore.Installed {
        val candidate = candidate(next)
        val prepared = WorkspaceSkillApprovedUpdate.prepare(current, candidate)
        return store.updateApprovedPackage(
            current.skill.name,
            candidate,
            prepared.request,
            prepared.request.approvalToken,
            at,
        )
    }

    @Test fun approvalBindsExactTransitionAndWarnsWhenBroaderPermissionsReturn() {
        val store = WorkspaceSkillStore(temp.newFolder("rollback-approval"))
        val sha = "1234567890abcdef1234567890abcdef12345678"
        val old = skill(
            "Old.",
            """{"allowedTools":["read_file"],"sourceSharing":"BOUNDED","memoryAccess":"READ","modelInvocable":true}""",
            WorkspaceSkillContract.Provenance(
                WorkspaceSkillContract.Origin.GITHUB_PINNED,
                "https://github.com/a/b/blob/" + sha + "/SKILL.md",
                sha,
            ),
        )
        val first = install(store, old)
        val current = update(
            store,
            first,
            skill(
                "New.",
                """{"allowedTools":[],"sourceSharing":"NONE","memoryAccess":"NONE","modelInvocable":false}""",
            ),
        )
        val rollback = store.loadRollback("review-code")
        val prepared = WorkspaceSkillApprovedRollback.prepare(current, rollback)

        assertEquals(current.entry.packageSha256, prepared.request.fromPackageSha256)
        assertEquals(rollback.entry.packageSha256, prepared.request.toPackageSha256)
        assertEquals(
            WorkspaceSkillRollbackPreview.Status.RESTORES_BROADER_PERMISSIONS,
            prepared.request.permissionEffect,
        )
        assertTrue(prepared.approvalSummary.contains("RESTORES BROADER PERMISSIONS"))
        assertTrue(prepared.approvalSummary.contains(sha))
        assertFalse(prepared.approvalSummary.contains(prepared.request.approvalToken))
    }

    @Test fun approvedRollbackSwitchesDisabledAndMakesReplacedVersionNextRollbackPoint() {
        val root = temp.newFolder("rollback-switch")
        val store = WorkspaceSkillStore(root)
        val old = skill("Old.", """{"allowedTools":["read_file"]}""")
        val first = install(store, old)
        val newer = skill("New.", """{"allowedTools":[]}""")
        val current = update(store, first, newer)
        val oldPackage = first.entry.packageSha256
        val newPackage = current.entry.packageSha256

        val rollback = store.loadRollback("review-code")
        val prepared = WorkspaceSkillApprovedRollback.prepare(current, rollback)
        val restored = store.rollbackApproved(
            "review-code",
            prepared.request,
            prepared.request.approvalToken,
            3L,
        )

        assertEquals(oldPackage, restored.entry.packageSha256)
        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED, restored.entry.state)
        assertNull(restored.entry.enabledAtMs)
        assertNull(restored.entry.enableReadinessSha256)
        assertNull(restored.entry.enableEnvironmentSha256)
        assertNull(restored.entry.enableBindingSha256)
        assertEquals(newPackage, requireNotNull(restored.entry.rollbackPoint).packageSha256)
        assertTrue(File(root, "packages/" + oldPackage).isDirectory)
        assertTrue(File(root, "packages/" + newPackage).isDirectory)

        val backTarget = store.loadRollback("review-code")
        assertEquals(newPackage, backTarget.entry.packageSha256)
        val backApproval = WorkspaceSkillApprovedRollback.prepare(restored, backTarget)
        val back = store.rollbackApproved(
            "review-code",
            backApproval.request,
            backApproval.request.approvalToken,
            4L,
        )
        assertEquals(newPackage, back.entry.packageSha256)
        assertEquals(oldPackage, requireNotNull(back.entry.rollbackPoint).packageSha256)
        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED, back.entry.state)
    }

    @Test fun staleStateWrongTokenAndTamperedRollbackFailClosed() {
        val root = temp.newFolder("rollback-fail-closed")
        val store = WorkspaceSkillStore(root)
        val first = install(store, skill("Old.", """{"allowedTools":[]}"""))
        var current = update(store, first, skill("New.", """{"allowedTools":[]}"""))
        val target = store.loadRollback("review-code")
        val prepared = WorkspaceSkillApprovedRollback.prepare(current, target)

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
        assertEquals(WorkspaceSkillCatalog.State.ENABLED, current.entry.state)
        assertTrue(runCatching {
            store.rollbackApproved(
                "review-code",
                prepared.request,
                prepared.request.approvalToken,
                5L,
            )
        }.isFailure)
        assertEquals(
            WorkspaceSkillCatalog.State.ENABLED,
            store.load("review-code").entry.state,
        )

        val freshTarget = store.loadRollback("review-code")
        val fresh = WorkspaceSkillApprovedRollback.prepare(current, freshTarget)
        assertTrue(runCatching {
            store.rollbackApproved("review-code", fresh.request, "wrong-token", 5L)
        }.isFailure)
        assertEquals(current.entry.packageSha256, store.load("review-code").entry.packageSha256)

        File(
            root,
            "packages/" + freshTarget.entry.packageSha256 + "/SKILL.md",
        ).appendText("\nTampered")
        assertTrue(runCatching {
            store.rollbackApproved(
                "review-code",
                fresh.request,
                fresh.request.approvalToken,
                5L,
            )
        }.isFailure)
        assertEquals(current.entry.packageSha256, store.load("review-code").entry.packageSha256)
    }
}
