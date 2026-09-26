package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSkillApprovedUpdateTest {
    @get:Rule val temp = TemporaryFolder()

    private fun parsed(
        description: String,
        manifest: String =
            """{"allowedTools":["read_file"],"sourceSharing":"BOUNDED","memoryAccess":"READ"}""",
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
    ): WorkspaceSkillStore.Installed {
        val packageFiles = files(skill)
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, packageFiles)
        val approval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        return store.install(skill, packageFiles, approval, approval.approvalToken, 1L)
    }

    private fun candidate(skill: WorkspaceSkillContract.ParsedSkill):
        WorkspaceSkillUpdatePreview.Candidate {
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

    @Test fun approvalBindsCurrentCandidatePermissionsProvenanceAndHidesToken() {
        val root = temp.newFolder("approved-update")
        val store = WorkspaceSkillStore(root)
        val current = install(store, parsed("Old."))
        val sha = "1234567890abcdef1234567890abcdef12345678"
        val next = parsed(
            "New.",
            provenance = WorkspaceSkillContract.Provenance(
                WorkspaceSkillContract.Origin.GITHUB_PINNED,
                "https://github.com/a/b/blob/" + sha + "/SKILL.md",
                sha,
            ),
        )
        val candidate = candidate(next)
        val prepared = WorkspaceSkillApprovedUpdate.prepare(current, candidate)

        assertEquals(current.snapshot.packageSha256, prepared.request.fromPackageSha256)
        assertEquals(candidate.snapshot.packageSha256, prepared.request.toPackageSha256)
        assertEquals(candidate.permissionSha256, prepared.request.toPermissionSha256)
        assertEquals(
            WorkspaceSkillContract.Origin.GITHUB_PINNED,
            prepared.request.toProvenance.origin,
        )
        assertTrue(prepared.approvalSummary.contains("Result: INSTALLED_DISABLED"))
        assertTrue(prepared.approvalSummary.contains(sha))
        assertFalse(prepared.approvalSummary.contains(prepared.request.approvalToken))
    }

    @Test fun approvedReplacementAppendsNewPackageKeepsOldAndStartsDisabled() {
        val root = temp.newFolder("replace")
        val store = WorkspaceSkillStore(root)
        val old = parsed("Old.")
        var current = install(store, old)

        val environment = WorkspaceSkillEnablement.Environment()
        val report = WorkspaceSkillEnablement.test(current, environment, 2L)
        val enable = WorkspaceSkillEnablement.enableRequest(current, report)
        current = store.enable(
            "review-code", environment, enable, enable.approvalToken, 3L)
        assertEquals(WorkspaceSkillCatalog.State.ENABLED, current.entry.state)

        val next = parsed(
            "New.",
            """{"allowedTools":[],"sourceSharing":"NONE","memoryAccess":"NONE"}""",
        )
        val candidate = candidate(next)
        val prepared = WorkspaceSkillApprovedUpdate.prepare(current, candidate)
        val oldPackage = current.snapshot.packageSha256

        val updated = store.updateApprovedPackage(
            name = "review-code",
            candidate = candidate,
            request = prepared.request,
            approvedToken = prepared.request.approvalToken,
            updatedAtMs = 4L,
        )

        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED, updated.entry.state)
        assertNull(updated.entry.enabledAtMs)
        assertNull(updated.entry.enableReadinessSha256)
        assertNull(updated.entry.enableEnvironmentSha256)
        assertNull(updated.entry.enableBindingSha256)
        assertEquals(candidate.snapshot.packageSha256, updated.snapshot.packageSha256)
        assertTrue(File(root, "packages/" + oldPackage).isDirectory)
        assertTrue(File(root, "packages/" + candidate.snapshot.packageSha256).isDirectory)
        assertNotEquals(oldPackage, updated.snapshot.packageSha256)
    }

    @Test fun staleCurrentStateChangedCandidateAndPermissionWideningFailClosed() {
        val root = temp.newFolder("stale")
        val store = WorkspaceSkillStore(root)
        var current = install(store, parsed("Old."))
        val safe = candidate(parsed(
            "Safe.",
            """{"allowedTools":[],"sourceSharing":"NONE","memoryAccess":"NONE"}""",
        ))
        val prepared = WorkspaceSkillApprovedUpdate.prepare(current, safe)

        val environment = WorkspaceSkillEnablement.Environment()
        val report = WorkspaceSkillEnablement.test(current, environment, 2L)
        val enable = WorkspaceSkillEnablement.enableRequest(current, report)
        current = store.enable(
            "review-code", environment, enable, enable.approvalToken, 3L)

        assertTrue(runCatching {
            WorkspaceSkillApprovedUpdate.validate(prepared, current, safe)
        }.isFailure)

        val changed = candidate(parsed(
            "Different.",
            """{"allowedTools":[],"sourceSharing":"NONE","memoryAccess":"NONE"}""",
        ))
        val freshPrepared = WorkspaceSkillApprovedUpdate.prepare(current, safe)
        assertTrue(runCatching {
            WorkspaceSkillApprovedUpdate.validate(freshPrepared, current, changed)
        }.isFailure)

        val widened = candidate(parsed(
            "Widened.",
            """{"allowedTools":["read_file","write_file"],"sourceSharing":"BOUNDED","memoryAccess":"READ_WRITE"}""",
        ))
        assertTrue(runCatching {
            WorkspaceSkillApprovedUpdate.prepare(current, widened)
        }.isFailure)
    }
}
