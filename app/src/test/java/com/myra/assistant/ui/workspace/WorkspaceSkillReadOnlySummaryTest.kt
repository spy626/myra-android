package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSkillReadOnlySummaryTest {
    private fun installed(): WorkspaceSkillStore.Installed {
        val skill = WorkspaceSkillContract.parse(
            """---
name: review-code
description: Review code with bounded evidence.
---
## Verification
Require deterministic verification before completion.
""",
            """{"allowedTools":["read_file"],"requiredCapabilities":["review"],"networkDomains":["example.com"],"sourceSharing":"BOUNDED","memoryAccess":"READ"}""",
            provenance = WorkspaceSkillContract.Provenance(
                WorkspaceSkillContract.Origin.GITHUB_PINNED,
                "https://github.com/example/skills/blob/1234567890abcdef1234567890abcdef12345678/SKILL.md",
                "1234567890abcdef1234567890abcdef12345678",
            ),
            packagePaths = listOf("SKILL.md", "skill.json"),
        )
        val files = linkedMapOf(
            "SKILL.md" to skill.originalSkillMd.toByteArray(),
            "skill.json" to requireNotNull(skill.originalSkillJson).toByteArray(),
        )
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, files)
        val approval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        val entry = WorkspaceSkillCatalog.Entry(
            name = skill.name,
            description = skill.description,
            contentSha256 = skill.contentSha256,
            packageSha256 = snapshot.packageSha256,
            permissionSha256 = approval.permissionSha256,
            provenance = skill.provenance,
            installedAtMs = 10L,
        )
        return WorkspaceSkillStore.Installed(entry, skill, snapshot)
    }

    @Test fun disabledSummaryIsReadOnlyAndShowsApprovedIdentity() {
        val item = WorkspaceSkillReadOnlySummary.from(installed())
        assertEquals("INSTALLED · DISABLED", item.stateLabel)
        assertEquals("GitHub · pinned revision", item.originLabel)
        assertTrue(item.permissionSummary.contains("Tools 1"))
        assertTrue(item.permissionSummary.contains("Network 1"))
        assertTrue(item.accessSummary.contains("Source bounded"))
        assertTrue(item.accessSummary.contains("Memory read"))
        assertTrue(item.activationSummary.contains("readiness approval required"))
        assertTrue(item.identitySummary.contains("permissions "))
    }

    @Test fun enabledSummaryShowsBoundReadinessAndEnvironmentFingerprints() {
        val base = installed()
        val environment = WorkspaceSkillEnablement.Environment(
            availableTools = setOf("read_file"),
            availableCapabilities = setOf("review"),
            boundedSourceGateAvailable = true,
            networkGateAvailable = true,
            memoryReadGateAvailable = true,
        )
        val report = WorkspaceSkillEnablement.test(base, environment, 20L)
        val request = WorkspaceSkillEnablement.enableRequest(base, report)
        val enabled = WorkspaceSkillEnablement.enabledEntry(
            base, environment, request, request.approvalToken, 21L)
        val item = WorkspaceSkillReadOnlySummary.from(
            WorkspaceSkillStore.Installed(enabled, base.skill, base.snapshot))

        assertEquals("ENABLED", item.stateLabel)
        assertTrue(item.activationSummary.contains(report.reportSha256.take(12)))
        assertTrue(item.activationSummary.contains(report.environmentSha256.take(12)))
    }
}
