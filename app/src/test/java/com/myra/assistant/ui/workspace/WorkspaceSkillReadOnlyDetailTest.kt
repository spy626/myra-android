package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSkillReadOnlyDetailTest {
    private fun installed(): WorkspaceSkillStore.Installed {
        val skill = WorkspaceSkillContract.parse(
            """---
name: review-code
description: Review code with bounded evidence.
license: MIT
compatibility: LYRA native instruction skill
---
## Verification
Require deterministic verification before completion.
""",
            """{"version":"1.2.0","author":"Example","requiredLyraVersion":"1","allowedTools":["write_file","read_file"],"requiredCapabilities":["review"],"networkDomains":["example.com"],"sourceSharing":"BOUNDED","memoryAccess":"READ","userInvocable":true,"modelInvocable":false,"maxNestingDepth":1}""",
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

    private fun value(detail: WorkspaceSkillReadOnlyDetail.Detail, label: String): String =
        detail.rows.first { it.label == label }.value

    @Test fun detailShowsExactApprovedPermissionsProvenanceAndFingerprints() {
        val base = installed()
        val detail = WorkspaceSkillReadOnlyDetail.from(base)

        assertEquals("INSTALLED · DISABLED", detail.stateLabel)
        assertEquals("read_file, write_file", value(detail, "Allowed tools"))
        assertEquals("review", value(detail, "Required capabilities"))
        assertEquals("example.com", value(detail, "Network domains"))
        assertEquals("BOUNDED", value(detail, "Source sharing"))
        assertEquals("READ", value(detail, "Memory access"))
        assertEquals("MIT", value(detail, "Declared license"))
        assertEquals("Declared", value(detail, "Verification gate"))
        assertEquals(base.entry.contentSha256, value(detail, "Content SHA-256"))
        assertEquals(base.entry.packageSha256, value(detail, "Package SHA-256"))
        assertEquals(base.entry.permissionSha256, value(detail, "Permission SHA-256"))
        assertEquals(
            "1234567890abcdef1234567890abcdef12345678",
            value(detail, "Pinned revision"),
        )
    }

    @Test fun enabledDetailShowsExactActivationBindings() {
        val base = installed()
        val environment = WorkspaceSkillEnablement.Environment(
            availableTools = setOf("read_file", "write_file"),
            availableCapabilities = setOf("review"),
            boundedSourceGateAvailable = true,
            networkGateAvailable = true,
            memoryReadGateAvailable = true,
        )
        val report = WorkspaceSkillEnablement.test(base, environment, 20L)
        val request = WorkspaceSkillEnablement.enableRequest(base, report)
        val enabledEntry = WorkspaceSkillEnablement.enabledEntry(
            base, environment, request, request.approvalToken, 21L)
        val detail = WorkspaceSkillReadOnlyDetail.from(
            WorkspaceSkillStore.Installed(enabledEntry, base.skill, base.snapshot))

        assertEquals("ENABLED", detail.stateLabel)
        assertEquals(report.reportSha256, value(detail, "Readiness SHA-256"))
        assertEquals(report.environmentSha256, value(detail, "Environment SHA-256"))
        assertEquals(
            requireNotNull(enabledEntry.enableBindingSha256),
            value(detail, "Activation binding SHA-256"),
        )
        assertTrue(value(detail, "Warnings").isNotBlank())
    }
}
