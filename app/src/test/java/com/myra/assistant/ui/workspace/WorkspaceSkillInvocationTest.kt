package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillInvocationTest {
    @get:Rule val temp = TemporaryFolder()

    private fun enabled(
        skillJson: String? = null,
        body: String = "## Verification\nConfirm deterministic evidence.",
        environment: WorkspaceSkillEnablement.Environment =
            WorkspaceSkillEnablement.Environment(),
    ): WorkspaceSkillStore.Installed {
        val md = """---
name: review-code
description: Review code safely.
---
$body
"""
        val paths = if (skillJson == null) listOf("SKILL.md")
        else listOf("SKILL.md", "skill.json")
        val skill = WorkspaceSkillContract.parse(md, skillJson, packagePaths = paths)
        val files = linkedMapOf("SKILL.md" to md.toByteArray())
        if (skillJson != null) files["skill.json"] = skillJson.toByteArray()
        val root = temp.newFolder()
        val store = WorkspaceSkillStore(root)
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, files)
        val installApproval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        val installed = store.install(
            skill, files, installApproval, installApproval.approvalToken, 10L)
        val report = WorkspaceSkillEnablement.test(installed, environment, 20L)
        val enable = WorkspaceSkillEnablement.enableRequest(installed, report)
        return store.enable(
            skill.name, environment, enable, enable.approvalToken, 30L)
    }

    private fun context(
        origin: WorkspaceSkillInvocation.Origin =
            WorkspaceSkillInvocation.Origin.USER_EXPLICIT,
        grants: WorkspaceSkillInvocation.Grants = WorkspaceSkillInvocation.Grants(),
        sourceRevision: String? = null,
        depth: Int = 0,
        budget: Int = 12_000,
    ) = WorkspaceSkillInvocation.Context(
        invocationId = "invoke-1",
        taskId = "task-1",
        turnId = "turn-1",
        sourceRevision = sourceRevision,
        origin = origin,
        nestingDepth = depth,
        grants = grants,
        maxProjectionChars = budget,
    )

    @Test fun enabledUserSkillProjectsBoundedInstructionsWithoutGrantingAuthority() {
        val skill = enabled()
        val projection = WorkspaceSkillInvocation.prepare(skill, context())
        assertEquals("review-code", projection.skillName)
        assertEquals(64, projection.invocationSha256.length)
        assertTrue(projection.prompt.contains("BEGIN SKILL INSTRUCTIONS"))
        assertTrue(projection.prompt.contains("do not grant tools", ignoreCase = true))
        assertTrue(projection.prompt.contains("local verification", ignoreCase = true))
    }

    @Test fun installedButDisabledSkillCannotBeInvoked() {
        val md = """---
name: review-code
description: Review code safely.
---
## Verification
Verify.
"""
        val skill = WorkspaceSkillContract.parse(md)
        val files = mapOf("SKILL.md" to md.toByteArray())
        val store = WorkspaceSkillStore(temp.newFolder())
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, files)
        val approval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        val installed = store.install(skill, files, approval, approval.approvalToken, 10L)

        assertTrue(runCatching {
            WorkspaceSkillInvocation.prepare(installed, context())
        }.isFailure)
    }

    @Test fun userAndModelInvocationFlagsAreEnforcedIndependently() {
        val json = """{"userInvocable":false,"modelInvocable":true}"""
        val env = WorkspaceSkillEnablement.Environment(
            modelInvocationGateAvailable = true)
        val skill = enabled(json, environment = env)

        assertTrue(runCatching {
            WorkspaceSkillInvocation.prepare(skill, context())
        }.isFailure)

        assertTrue(runCatching {
            WorkspaceSkillInvocation.prepare(
                skill,
                context(origin = WorkspaceSkillInvocation.Origin.MODEL_SELECTED))
        }.isFailure)

        val model = WorkspaceSkillInvocation.prepare(
            skill,
            context(
                origin = WorkspaceSkillInvocation.Origin.MODEL_SELECTED,
                grants = WorkspaceSkillInvocation.Grants(
                    modelSelectionApproved = true),
            )
        )
        assertEquals(WorkspaceSkillInvocation.Origin.MODEL_SELECTED, model.origin)
    }

    @Test fun sensitivePermissionsAreRecheckedPerInvocation() {
        val json = """{
          "allowedTools":["read_file"],
          "requiredCapabilities":["project:read"],
          "networkDomains":["api.github.com"],
          "sourceSharing":"BOUNDED",
          "memoryAccess":"READ_WRITE",
          "dependencySkills":["base-review"],
          "maxNestingDepth":1
        }"""
        val env = WorkspaceSkillEnablement.Environment(
            availableTools = setOf("read_file"),
            availableCapabilities = setOf("project:read"),
            installedSkills = setOf("base-review"),
            boundedSourceGateAvailable = true,
            networkGateAvailable = true,
            memoryReadGateAvailable = true,
            memoryWriteGateAvailable = true,
        )
        val skill = enabled(json, environment = env)

        assertTrue(runCatching {
            WorkspaceSkillInvocation.prepare(skill, context())
        }.isFailure)

        val projection = WorkspaceSkillInvocation.prepare(
            skill,
            context(
                grants = WorkspaceSkillInvocation.Grants(
                    tools = setOf("read_file"),
                    capabilities = setOf("project:read"),
                    networkDomains = setOf("api.github.com"),
                    sourceApproved = true,
                    memoryReadApproved = true,
                    memoryWriteApproved = true,
                    enabledSkills = setOf("base-review"),
                ),
                sourceRevision = "source-1",
                depth = 1,
            )
        )
        assertEquals("source-1", projection.sourceRevision)
        assertFalse(projection.prompt.contains("project source bytes", ignoreCase = true))
    }

    @Test fun disabledDependencyOrWrongNetworkDomainBlocksLocally() {
        val json = """{
          "networkDomains":["api.github.com"],
          "dependencySkills":["base-review"]
        }"""
        val env = WorkspaceSkillEnablement.Environment(
            installedSkills = setOf("base-review"),
            networkGateAvailable = true)
        val skill = enabled(json, environment = env)

        assertTrue(runCatching {
            WorkspaceSkillInvocation.prepare(
                skill,
                context(grants = WorkspaceSkillInvocation.Grants(
                    networkDomains = setOf("example.com"),
                    enabledSkills = setOf("base-review"),
                ))
            )
        }.isFailure)

        assertTrue(runCatching {
            WorkspaceSkillInvocation.prepare(
                skill,
                context(grants = WorkspaceSkillInvocation.Grants(
                    networkDomains = setOf("api.github.com"),
                    enabledSkills = emptySet(),
                ))
            )
        }.isFailure)
    }

    @Test fun possibleSecretInSkillBodyBlocksAtReadinessBeforeEnable() {
        val md = """---
name: secret-skill
description: Bad skill.
---
## Verification
api_key = sk-abcdefghijklmnop
"""
        val skill = WorkspaceSkillContract.parse(md)
        val files = mapOf("SKILL.md" to md.toByteArray())
        val store = WorkspaceSkillStore(temp.newFolder())
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, files)
        val approval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        val installed = store.install(skill, files, approval, approval.approvalToken, 10L)
        val report = WorkspaceSkillEnablement.test(
            installed, WorkspaceSkillEnablement.Environment(), 20L)

        assertEquals(WorkspaceSkillEnablement.Status.BLOCKED, report.status)
        assertFalse(report.checks.first { it.id == "secret-screen" }.passed)
    }

    @Test fun oversizedSkillIsNotSilentlyTruncatedIntoPrompt() {
        val body = "## Verification\n" + "x".repeat(13_000)
        val skill = enabled(body = body)
        assertTrue(runCatching {
            WorkspaceSkillInvocation.prepare(
                skill, context(budget = 12_000))
        }.isFailure)
    }
}
