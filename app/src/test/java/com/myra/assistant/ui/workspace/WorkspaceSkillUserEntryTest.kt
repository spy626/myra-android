package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillUserEntryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun installEnabled(
        store: WorkspaceSkillStore,
        name: String = "review-code",
        skillJson: String? = null,
        environment: WorkspaceSkillEnablement.Environment =
            WorkspaceSkillEnablement.Environment(),
    ): WorkspaceSkillStore.Installed {
        val md = """---
name: $name
description: Safe explicit test skill.
---
## Verification
Confirm deterministic evidence before completion.
"""
        val packagePaths = if (skillJson == null) listOf("SKILL.md")
            else listOf("SKILL.md", "skill.json")
        val parsed = WorkspaceSkillContract.parse(
            md, skillJson, packagePaths = packagePaths)
        val files = linkedMapOf("SKILL.md" to md.toByteArray())
        if (skillJson != null) files["skill.json"] = skillJson.toByteArray()
        val snapshot = WorkspaceSkillCatalog.snapshot(parsed, files)
        val approval = WorkspaceSkillCatalog.approvalRequest(parsed, snapshot)
        val installed = store.install(
            parsed, files, approval, approval.approvalToken, 10L)
        val report = WorkspaceSkillEnablement.test(installed, environment, 20L)
        assertEquals(WorkspaceSkillEnablement.Status.PASS, report.status)
        val enable = WorkspaceSkillEnablement.enableRequest(installed, report)
        return store.enable(name, environment, enable, enable.approvalToken, 30L)
    }

    @Test fun parserClaimsOnlyExactExplicitSkillCommand() {
        assertNull(WorkspaceSkillUserCommand.parse("normal chat"))
        assertNull(WorkspaceSkillUserCommand.parse("/skills review-code task"))
        val parsed = requireNotNull(
            WorkspaceSkillUserCommand.parse("/skill REVIEW-CODE   check this response"))
        assertEquals("review-code", parsed.skillName)
        assertEquals("check this response", parsed.task)

        assertTrue(runCatching { WorkspaceSkillUserCommand.parse("/skill") }.isFailure)
        assertTrue(runCatching {
            WorkspaceSkillUserCommand.parse("/skill review-code")
        }.isFailure)
    }

    @Test fun explicitEnabledSkillBuildsUserOnlyFreshProjection() {
        val store = WorkspaceSkillStore(temp.newFolder())
        installEnabled(store)
        val prepared = requireNotNull(WorkspaceSkillUserEntry.prepare(
            store, "project-1", "turn-1",
            "/skill review-code review this answer"))
        assertEquals("review-code", prepared.command.skillName)
        assertEquals("review this answer", prepared.command.task)
        assertEquals(WorkspaceSkillInvocation.Origin.USER_EXPLICIT,
            prepared.projection.origin)
        assertEquals("turn-1", prepared.projection.turnId)
        assertTrue(prepared.projection.prompt.contains("Skill: review-code"))
        assertTrue(prepared.projection.prompt.contains("BEGIN SKILL INSTRUCTIONS"))
    }

    @Test fun disabledMissingOrModelOnlySkillFailsClosed() {
        val disabledStore = WorkspaceSkillStore(temp.newFolder())
        val md = """---
name: review-code
description: Disabled.
---
## Verification
Verify.
"""
        val parsed = WorkspaceSkillContract.parse(md)
        val files = mapOf("SKILL.md" to md.toByteArray())
        val snapshot = WorkspaceSkillCatalog.snapshot(parsed, files)
        val approval = WorkspaceSkillCatalog.approvalRequest(parsed, snapshot)
        disabledStore.install(parsed, files, approval, approval.approvalToken, 1L)
        assertTrue(runCatching {
            WorkspaceSkillUserEntry.prepare(
                disabledStore, "p1", "t1", "/skill review-code task")
        }.isFailure)

        assertTrue(runCatching {
            WorkspaceSkillUserEntry.prepare(
                WorkspaceSkillStore(temp.newFolder()), "p1", "t1",
                "/skill missing task")
        }.isFailure)

        val modelStore = WorkspaceSkillStore(temp.newFolder())
        val env = WorkspaceSkillEnablement.Environment(
            modelInvocationGateAvailable = true)
        installEnabled(
            modelStore,
            skillJson = """{"userInvocable":false,"modelInvocable":true}""",
            environment = env)
        assertTrue(runCatching {
            WorkspaceSkillUserEntry.prepare(
                modelStore, "p1", "t1", "/skill review-code task")
        }.isFailure)
    }

    @Test fun privilegedSkillDoesNotGainSensitivePermissionsFromCommand() {
        val store = WorkspaceSkillStore(temp.newFolder())
        val env = WorkspaceSkillEnablement.Environment(
            availableTools = setOf("read_file"),
            availableCapabilities = setOf("project:read"),
            boundedSourceGateAvailable = true,
            networkGateAvailable = true,
            memoryReadGateAvailable = true,
        )
        installEnabled(
            store,
            skillJson = """{
              "allowedTools":["read_file"],
              "requiredCapabilities":["project:read"],
              "networkDomains":["api.github.com"],
              "sourceSharing":"BOUNDED",
              "memoryAccess":"READ"
            }""",
            environment = env,
        )
        assertTrue(runCatching {
            WorkspaceSkillUserEntry.prepare(
                store, "p1", "t1", "/skill review-code inspect source")
        }.isFailure)
    }

    @Test fun enabledDependencyMayBeSatisfiedButDoesNotGrantOtherAuthority() {
        val store = WorkspaceSkillStore(temp.newFolder())
        installEnabled(store, name = "base-review")
        val env = WorkspaceSkillEnablement.Environment(
            installedSkills = setOf("base-review"),
            enabledSkills = setOf("base-review"),
        )
        installEnabled(
            store,
            name = "review-code",
            skillJson = """{"dependencySkills":["base-review"]}""",
            environment = env,
        )
        val prepared = requireNotNull(WorkspaceSkillUserEntry.prepare(
            store, "p1", "t1", "/skill review-code task"))
        assertEquals("review-code", prepared.projection.skillName)
    }
}
