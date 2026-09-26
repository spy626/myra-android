package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillRollbackCatalogTest {
    @get:Rule val temp = TemporaryFolder()

    private fun skill(description: String): WorkspaceSkillContract.ParsedSkill =
        WorkspaceSkillContract.parse(
            """---
name: review-code
description: """ + description + """
---
## Verification
Check.
""",
        )

    @Test fun rollbackPointPersistsInsideAuthoritativeCatalogAcrossReopen() {
        val root = temp.newFolder("rollback-catalog")
        val store = WorkspaceSkillStore(root)
        val old = skill("Old.")
        val oldFiles = mapOf("SKILL.md" to old.originalSkillMd.toByteArray())
        val oldSnapshot = WorkspaceSkillCatalog.snapshot(old, oldFiles)
        val oldApproval = WorkspaceSkillCatalog.approvalRequest(old, oldSnapshot)
        val current = store.install(
            old, oldFiles, oldApproval, oldApproval.approvalToken, 1L)

        val nextSkill = skill("New.")
        val nextFiles = mapOf("SKILL.md" to nextSkill.originalSkillMd.toByteArray())
        val nextSnapshot = WorkspaceSkillCatalog.snapshot(nextSkill, nextFiles)
        val candidate = WorkspaceSkillUpdatePreview.Candidate(
            skill = nextSkill,
            snapshot = nextSnapshot,
            permissionSha256 =
                WorkspaceSkillCatalog.approvalRequest(nextSkill, nextSnapshot).permissionSha256,
            packageFiles = nextFiles,
        )
        val prepared = WorkspaceSkillApprovedUpdate.prepare(current, candidate)
        store.updateApprovedPackage(
            "review-code", candidate, prepared.request, prepared.request.approvalToken, 2L)

        val reopened = WorkspaceSkillStore(root).load("review-code")
        val point = requireNotNull(reopened.entry.rollbackPoint)
        assertEquals(oldSnapshot.packageSha256, point.packageSha256)
        assertEquals(old.contentSha256, point.contentSha256)
        assertEquals(current.entry.permissionSha256, point.permissionSha256)
        assertEquals(current.entry.provenance, point.provenance)
    }
}
