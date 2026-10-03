package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSkillRollbackPreviewTest {
    @get:Rule val temp = TemporaryFolder()

    private fun parsed(
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
Require deterministic evidence.
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
        at: Long,
    ): WorkspaceSkillStore.Installed {
        val bytes = files(skill)
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, bytes)
        val approval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        return store.install(skill, bytes, approval, approval.approvalToken, at)
    }

    private fun candidate(
        skill: WorkspaceSkillContract.ParsedSkill,
    ): WorkspaceSkillUpdatePreview.Candidate {
        val bytes = files(skill)
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, bytes)
        return WorkspaceSkillUpdatePreview.Candidate(
            skill = skill,
            snapshot = snapshot,
            permissionSha256 =
                WorkspaceSkillCatalog.approvalRequest(skill, snapshot).permissionSha256,
            packageFiles = bytes.mapValues { it.value.copyOf() },
        )
    }

    @Test fun updateRecordsExactRollbackAndPreviewDoesNotMutate() {
        val root = temp.newFolder("rollback-preview")
        val store = WorkspaceSkillStore(root)
        val sha = "1234567890abcdef1234567890abcdef12345678"
        val old = parsed(
            "Older.",
            """{"allowedTools":["read_file"],"sourceSharing":"BOUNDED","memoryAccess":"READ","modelInvocable":true}""",
            WorkspaceSkillContract.Provenance(
                WorkspaceSkillContract.Origin.GITHUB_PINNED,
                "https://github.com/a/b/blob/" + sha + "/SKILL.md",
                sha,
            ),
        )
        val current = install(store, old, 10L)
        val newer = parsed(
            "Newer.",
            """{"allowedTools":[],"sourceSharing":"NONE","memoryAccess":"NONE","modelInvocable":false}""",
        )
        val next = candidate(newer)
        val approval = WorkspaceSkillApprovedUpdate.prepare(current, next)
        val updated = store.updateApprovedPackage(
            "review-code", next, approval.request, approval.request.approvalToken, 20L)

        val before = store.readCatalog()
        val rollback = store.loadRollback("review-code")
        val preview = WorkspaceSkillRollbackPreview.compare(updated, rollback)
        val after = store.readCatalog()

        assertEquals(before, after)
        assertEquals(current.entry.packageSha256, rollback.entry.packageSha256)
        assertEquals(current.entry.permissionSha256, rollback.entry.permissionSha256)
        assertEquals(current.entry.provenance, rollback.entry.provenance)
        assertEquals(
            WorkspaceSkillRollbackPreview.Status.RESTORES_BROADER_PERMISSIONS,
            preview.status,
        )
        assertEquals(
            listOf("read_file"),
            preview.setDeltas.first { it.label == "Allowed tools" }.restored,
        )
        assertEquals(
            "RESTORED",
            preview.scalarDeltas.first { it.label == "Memory access" }.classification,
        )
        assertTrue(preview.rollbackProvenance.contains(sha))
        assertTrue(preview.activationImpact.contains("INSTALLED_DISABLED"))
    }

    @Test fun tamperedRetainedPackageFailsClosedAndCurrentStillLoads() {
        val root = temp.newFolder("rollback-tamper")
        val store = WorkspaceSkillStore(root)
        val old = parsed("Old.", """{"allowedTools":["read_file"]}""")
        val current = install(store, old, 10L)
        val newer = parsed("New.", """{"allowedTools":[]}""")
        val next = candidate(newer)
        val approval = WorkspaceSkillApprovedUpdate.prepare(current, next)
        val updated = store.updateApprovedPackage(
            "review-code", next, approval.request, approval.request.approvalToken, 20L)

        File(
            root,
            "packages/" + current.entry.packageSha256 + "/SKILL.md",
        ).appendText("\nTampered")

        assertTrue(runCatching { store.loadRollback("review-code") }.isFailure)
        assertEquals(updated.entry.packageSha256, store.load("review-code").entry.packageSha256)
    }

    @Test fun freshInstallHasNoRollbackPoint() {
        val store = WorkspaceSkillStore(temp.newFolder("no-rollback"))
        val installed = install(store, parsed("Only.", """{"allowedTools":[]}"""), 1L)
        assertNull(installed.entry.rollbackPoint)
        assertTrue(runCatching { store.loadRollback("review-code") }.isFailure)
    }
}
