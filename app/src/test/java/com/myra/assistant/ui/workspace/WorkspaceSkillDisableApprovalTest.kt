package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillDisableApprovalTest {
    @get:Rule val temp = TemporaryFolder()

    private fun enabled(
        store: WorkspaceSkillStore,
        enabledAtMs: Long = 30L,
    ): WorkspaceSkillStore.Installed {
        val md = """---
name: safe-review
description: Review safely.
---
## Verification
Require deterministic verification.
"""
        val skill = WorkspaceSkillContract.parse(md)
        val files = mapOf("SKILL.md" to md.toByteArray())
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, files)
        val installApproval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        val installed = store.install(
            skill, files, installApproval, installApproval.approvalToken, 10L)
        val environment = WorkspaceSkillEnablement.Environment()
        val report = WorkspaceSkillEnablement.test(installed, environment, 20L)
        val enable = WorkspaceSkillEnablement.enableRequest(installed, report)
        return store.enable(
            skill.name, environment, enable, enable.approvalToken, enabledAtMs)
    }

    @Test fun enabledActivationProducesHumanSummaryWithoutRenderingApprovalToken() {
        val store = WorkspaceSkillStore(temp.newFolder("disable-summary"))
        val enabled = enabled(store)
        val prepared = WorkspaceSkillDisableApproval.prepare(enabled)

        assertEquals("safe-review", prepared.skillName)
        assertTrue(prepared.approvalSummary.contains(
            requireNotNull(enabled.entry.enableBindingSha256).take(12)))
        assertTrue(prepared.approvalSummary.contains("package will remain"))
        assertFalse(prepared.approvalSummary.contains(prepared.request.approvalToken))
    }

    @Test fun disabledSkillCannotCreateDisableApproval() {
        val store = WorkspaceSkillStore(temp.newFolder("already-disabled"))
        val enabled = enabled(store)
        val prepared = WorkspaceSkillDisableApproval.prepare(enabled)
        val disabled = store.disable(
            enabled.entry.name,
            prepared.request,
            prepared.request.approvalToken,
        )

        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED, disabled.entry.state)
        assertTrue(runCatching {
            WorkspaceSkillDisableApproval.prepare(disabled)
        }.isFailure)
    }

    @Test fun oldDisableApprovalCannotDisableANewActivation() {
        val store = WorkspaceSkillStore(temp.newFolder("stale-disable"))
        val firstEnabled = enabled(store, 30L)
        val oldApproval = WorkspaceSkillDisableApproval.prepare(firstEnabled)
        val disabled = store.disable(
            firstEnabled.entry.name,
            oldApproval.request,
            oldApproval.request.approvalToken,
        )

        val environment = WorkspaceSkillEnablement.Environment()
        val report = WorkspaceSkillEnablement.test(disabled, environment, 40L)
        val enable = WorkspaceSkillEnablement.enableRequest(disabled, report)
        val secondEnabled = store.enable(
            disabled.entry.name, environment, enable, enable.approvalToken, 50L)

        assertTrue(
            secondEnabled.entry.enableBindingSha256 !=
                firstEnabled.entry.enableBindingSha256
        )
        assertTrue(runCatching {
            store.disable(
                secondEnabled.entry.name,
                oldApproval.request,
                oldApproval.request.approvalToken,
            )
        }.isFailure)
        assertEquals(
            WorkspaceSkillCatalog.State.ENABLED,
            store.load(secondEnabled.entry.name).entry.state,
        )
    }
}
