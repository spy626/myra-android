package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillInstallApprovalTest {
    @get:Rule val temp = TemporaryFolder()

    private fun md(description: String = "Install safely."): ByteArray =
        """---
name: local-install
description: """ .plus(description).plus(
            """
---
## Verification
Require deterministic verification.
"""
        ).toByteArray()

    @Test fun cleanPreviewProducesExactHumanInstallSummaryWithoutToken() {
        val prepared = WorkspaceSkillInstallApproval.prepare(md())

        assertEquals("local-install", prepared.skillName)
        assertEquals(64, prepared.contentSha256.length)
        assertEquals(64, prepared.packageSha256.length)
        assertEquals(64, prepared.permissionSha256.length)
        assertTrue(prepared.approvalSummary.contains("Result: INSTALLED_DISABLED"))
        assertTrue(prepared.approvalSummary.contains(prepared.packageSha256.take(12)))
        assertFalse(prepared.approvalSummary.contains(prepared.approval.approvalToken))
    }

    @Test fun secretBlockedPreviewCannotProduceInstallApproval() {
        val secret = md("api_key = sk-abcdefghijklmnop")
        assertTrue(runCatching {
            WorkspaceSkillInstallApproval.prepare(secret)
        }.isFailure)
    }

    @Test fun changedBytesAfterHumanReviewFailClosed() {
        val original = md("Install safely.")
        val prepared = WorkspaceSkillInstallApproval.prepare(original)
        val changed = md("Install safely with changed text.")

        assertTrue(runCatching {
            WorkspaceSkillInstallApproval.revalidate(prepared, changed)
        }.isFailure)
    }

    @Test fun approvedNewInstallPersistsImmutablePackageDisabled() {
        val store = WorkspaceSkillStore(temp.newFolder("install-new"))
        val bytes = md()
        val prepared = WorkspaceSkillInstallApproval.prepare(bytes)
        val fresh = WorkspaceSkillInstallApproval.revalidate(prepared, bytes)

        val installed = store.installNew(
            skill = fresh.skill,
            packageFiles = fresh.packageFiles,
            approval = fresh.approval,
            approvedToken = fresh.approval.approvalToken,
            installedAtMs = 100L,
        )

        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED, installed.entry.state)
        assertNull(installed.entry.enabledAtMs)
        assertNull(installed.entry.enableReadinessSha256)
        assertNull(installed.entry.enableEnvironmentSha256)
        assertNull(installed.entry.enableBindingSha256)
        assertEquals(prepared.packageSha256, installed.snapshot.packageSha256)
        assertEquals(prepared.contentSha256, installed.entry.contentSha256)

        assertTrue(runCatching {
            store.installNew(
                skill = fresh.skill,
                packageFiles = fresh.packageFiles,
                approval = fresh.approval,
                approvedToken = fresh.approval.approvalToken,
                installedAtMs = 101L,
            )
        }.isFailure)
    }
}
