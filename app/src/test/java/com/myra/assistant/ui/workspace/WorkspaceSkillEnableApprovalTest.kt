package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSkillEnableApprovalTest {
    private fun installed(
        manifestJson: String? = null,
    ): WorkspaceSkillStore.Installed {
        val skill = WorkspaceSkillContract.parse(
            """---
name: safe-review
description: Review safely.
---
## Verification
Require deterministic verification.
""",
            manifestJson,
            packagePaths = if (manifestJson == null) listOf("SKILL.md")
            else listOf("SKILL.md", "skill.json"),
        )
        val files = linkedMapOf<String, ByteArray>(
            "SKILL.md" to skill.originalSkillMd.toByteArray(),
        )
        if (manifestJson != null) {
            files["skill.json"] = requireNotNull(skill.originalSkillJson).toByteArray()
        }
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, files)
        val installApproval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        val entry = WorkspaceSkillCatalog.Entry(
            name = skill.name,
            description = skill.description,
            contentSha256 = skill.contentSha256,
            packageSha256 = snapshot.packageSha256,
            permissionSha256 = installApproval.permissionSha256,
            provenance = skill.provenance,
            installedAtMs = 1L,
        )
        return WorkspaceSkillStore.Installed(entry, skill, snapshot)
    }

    @Test fun freshPassProducesExactHumanSummaryWithoutRenderingApprovalToken() {
        val installed = installed()
        val environment = WorkspaceSkillReadinessSurface.currentEnvironment(listOf(installed))
        val report = WorkspaceSkillEnablement.test(installed, environment, 10L)
        val prepared = WorkspaceSkillEnableApproval.prepare(installed, environment, report)

        assertEquals("safe-review", prepared.skillName)
        assertEquals(report.reportSha256, prepared.request.readinessSha256)
        assertTrue(prepared.approvalSummary.contains(report.reportSha256.take(12)))
        assertTrue(prepared.approvalSummary.contains(installed.entry.permissionSha256.take(12)))
        assertFalse(prepared.approvalSummary.contains(prepared.request.approvalToken))
    }

    @Test fun blockedReadinessCannotCreateHumanEnableApproval() {
        val installed = installed("""{"networkDomains":["example.com"]}""")
        val environment = WorkspaceSkillReadinessSurface.currentEnvironment(listOf(installed))
        val report = WorkspaceSkillEnablement.test(installed, environment, 10L)

        assertEquals(WorkspaceSkillEnablement.Status.BLOCKED, report.status)
        assertTrue(runCatching {
            WorkspaceSkillEnableApproval.prepare(installed, environment, report)
        }.isFailure)
    }

    @Test fun approvalTokenFailsWhenCurrentEnvironmentChanges() {
        val installed = installed()
        val environment = WorkspaceSkillReadinessSurface.currentEnvironment(listOf(installed))
        val report = WorkspaceSkillEnablement.test(installed, environment, 10L)
        val prepared = WorkspaceSkillEnableApproval.prepare(installed, environment, report)
        val changedEnvironment = environment.copy(installedSkills = setOf("other-skill"))

        assertTrue(runCatching {
            WorkspaceSkillEnablement.enabledEntry(
                installed,
                changedEnvironment,
                prepared.request,
                prepared.request.approvalToken,
                11L,
            )
        }.isFailure)
    }
}
