package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillEnablementTest {
    @get:Rule val temp = TemporaryFolder()

    private fun installed(
        skillJson: String? = null,
        body: String = "## Verification\nConfirm deterministic evidence.",
    ): WorkspaceSkillStore.Installed {
        val md = """---
name: review-code
description: Review code safely.
---
$body
"""
        val paths = if (skillJson == null) listOf("SKILL.md")
        else listOf("SKILL.md", "skill.json")
        val skill = WorkspaceSkillContract.parse(
            md, skillJson, packagePaths = paths)
        val files = linkedMapOf("SKILL.md" to md.toByteArray())
        if (skillJson != null) files["skill.json"] = skillJson.toByteArray()
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, files)
        val approval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        return WorkspaceSkillStore(temp.newFolder()).install(
            skill, files, approval, approval.approvalToken, 10L)
    }

    @Test fun minimalVerifiedSkillPassesReadinessAndGetsBoundEnableRequest() {
        val installed = installed()
        val report = WorkspaceSkillEnablement.test(
            installed,
            WorkspaceSkillEnablement.Environment(),
            testedAtMs = 20L,
        )
        assertEquals(WorkspaceSkillEnablement.Status.PASS, report.status)
        assertTrue(report.checks.all { it.passed })
        assertEquals(64, report.reportSha256.length)

        val request = WorkspaceSkillEnablement.enableRequest(installed, report)
        assertEquals(installed.entry.packageSha256, request.packageSha256)
        assertEquals(installed.entry.permissionSha256, request.permissionSha256)
        assertTrue(request.approvalToken.startsWith("lyra-skill-enable-v1:"))
    }

    @Test fun missingVerificationGateBlocksEnablement() {
        val installed = installed(body = "Follow the instructions carefully.")
        val report = WorkspaceSkillEnablement.test(
            installed, WorkspaceSkillEnablement.Environment(), 20L)
        assertEquals(WorkspaceSkillEnablement.Status.BLOCKED, report.status)
        assertFalse(report.checks.first { it.id == "verification-gate" }.passed)
        assertTrue(runCatching {
            WorkspaceSkillEnablement.enableRequest(installed, report)
        }.isFailure)
    }

    @Test fun unavailableToolsCapabilitiesAndDependenciesBlockLocally() {
        val json = """{
          "allowedTools":["read_file"],
          "requiredCapabilities":["project:read"],
          "dependencySkills":["base-review"]
        }"""
        val installed = installed(json)
        val blocked = WorkspaceSkillEnablement.test(
            installed, WorkspaceSkillEnablement.Environment(), 20L)
        assertEquals(WorkspaceSkillEnablement.Status.BLOCKED, blocked.status)
        assertFalse(blocked.checks.first { it.id == "tools" }.passed)
        assertFalse(blocked.checks.first { it.id == "capabilities" }.passed)
        assertFalse(blocked.checks.first { it.id == "dependencies" }.passed)

        val pass = WorkspaceSkillEnablement.test(
            installed,
            WorkspaceSkillEnablement.Environment(
                availableTools = setOf("read_file"),
                availableCapabilities = setOf("project:read"),
                installedSkills = setOf("base-review"),
                enabledSkills = setOf("base-review"),
            ),
            21L,
        )
        assertEquals(WorkspaceSkillEnablement.Status.PASS, pass.status)
    }

    @Test fun installedButDisabledDependencyBlocksReadiness() {
        val json = """{
          "dependencySkills":["base-review"]
        }"""
        val installed = installed(json)
        val report = WorkspaceSkillEnablement.test(
            installed,
            WorkspaceSkillEnablement.Environment(
                installedSkills = setOf("base-review"),
                enabledSkills = emptySet(),
            ),
            21L,
        )

        assertEquals(WorkspaceSkillEnablement.Status.BLOCKED, report.status)
        assertTrue(report.checks.first { it.id == "dependencies" }.passed)
        assertFalse(report.checks.first { it.id == "dependency-activation" }.passed)
        assertTrue(runCatching {
            WorkspaceSkillEnablement.enableRequest(installed, report)
        }.isFailure)
    }

    @Test fun sensitiveCapabilitiesNeedIndependentGates() {
        val json = """{
          "networkDomains":["api.github.com"],
          "sourceSharing":"BOUNDED",
          "memoryAccess":"READ_WRITE",
          "modelInvocable":true
        }"""
        val installed = installed(json)
        val blocked = WorkspaceSkillEnablement.test(
            installed, WorkspaceSkillEnablement.Environment(), 20L)
        assertEquals(WorkspaceSkillEnablement.Status.BLOCKED, blocked.status)

        val pass = WorkspaceSkillEnablement.test(
            installed,
            WorkspaceSkillEnablement.Environment(
                boundedSourceGateAvailable = true,
                networkGateAvailable = true,
                memoryReadGateAvailable = true,
                memoryWriteGateAvailable = true,
                modelInvocationGateAvailable = true,
            ),
            21L,
        )
        assertEquals(WorkspaceSkillEnablement.Status.PASS, pass.status)
    }

    @Test fun readinessReportCannotBeReusedForDifferentImmutablePackage() {
        val first = installed()
        val report = WorkspaceSkillEnablement.test(
            first, WorkspaceSkillEnablement.Environment(), 20L)

        val secondMd = """---
name: review-code
description: Changed skill.
---
## Verification
Confirm deterministic evidence.
"""
        val secondSkill = WorkspaceSkillContract.parse(secondMd)
        val files = mapOf("SKILL.md" to secondMd.toByteArray())
        val snapshot = WorkspaceSkillCatalog.snapshot(secondSkill, files)
        val approval = WorkspaceSkillCatalog.approvalRequest(secondSkill, snapshot)
        val second = WorkspaceSkillStore(temp.newFolder()).install(
            secondSkill, files, approval, approval.approvalToken, 11L)

        assertTrue(runCatching {
            WorkspaceSkillEnablement.enableRequest(second, report)
        }.isFailure)
    }

    @Test fun tamperedReadinessReportIsRejected() {
        val installed = installed()
        val report = WorkspaceSkillEnablement.test(
            installed, WorkspaceSkillEnablement.Environment(), 20L)
        val tampered = report.copy(
            checks = report.checks.map {
                if (it.id == "tools") it.copy(detail = "Different detail") else it
            }
        )
        assertTrue(runCatching {
            WorkspaceSkillEnablement.enableRequest(installed, tampered)
        }.isFailure)
    }
}
