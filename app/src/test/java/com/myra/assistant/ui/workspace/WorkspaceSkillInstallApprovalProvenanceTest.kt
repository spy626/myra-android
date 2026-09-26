package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSkillInstallApprovalProvenanceTest {
    @Test fun githubProvenanceChangesApprovalBindingAndIsRevalidatedExactly() {
        val sha = "1234567890abcdef1234567890abcdef12345678"
        val source = "https://github.com/a/b/blob/" + sha + "/SKILL.md"
        val md = """---
name: provenance-review
description: Test pinned provenance.
---
## Verification
Require deterministic verification.
""".toByteArray()
        val provenance = WorkspaceSkillContract.Provenance(
            origin = WorkspaceSkillContract.Origin.GITHUB_PINNED,
            sourceUrl = source,
            pinnedRevision = sha,
        )
        val github = WorkspaceSkillInstallApproval.prepare(
            skillMdBytes = md,
            provenance = provenance,
        )
        val local = WorkspaceSkillInstallApproval.prepare(md)

        assertTrue(github.approvalSummary.contains(source))
        assertTrue(github.approvalSummary.contains("Pinned revision: " + sha))
        assertFalse(github.approvalSummary.contains(github.approval.approvalToken))
        assertTrue(github.approval.approvalToken != local.approval.approvalToken)

        val fresh = WorkspaceSkillInstallApproval.revalidate(
            prepared = github,
            skillMdBytes = md,
            provenance = provenance,
        )
        assertEquals(WorkspaceSkillContract.Origin.GITHUB_PINNED, fresh.skill.provenance.origin)

        assertTrue(runCatching {
            WorkspaceSkillInstallApproval.revalidate(
                prepared = github,
                skillMdBytes = md,
            )
        }.isFailure)
    }
}
