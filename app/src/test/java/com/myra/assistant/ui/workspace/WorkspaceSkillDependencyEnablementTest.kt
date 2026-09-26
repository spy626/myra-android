package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillDependencyEnablementTest {
    @get:Rule val temp = TemporaryFolder()

    private fun skill(
        name: String,
        dependencies: List<String> = emptyList(),
    ): WorkspaceSkillContract.ParsedSkill {
        val json = buildString {
            append("{\"allowedTools\":[]")
            if (dependencies.isNotEmpty()) {
                append(",\"dependencySkills\":[")
                append(dependencies.joinToString(",") { "\"" + it + "\"" })
                append("]")
            }
            append("}")
        }
        return WorkspaceSkillContract.parse(
            """---
name: $name
description: $name skill.
---
## Verification
Check exact local evidence.
""",
            json,
            packagePaths = listOf("SKILL.md", "skill.json"),
        )
    }

    private fun files(skill: WorkspaceSkillContract.ParsedSkill) = linkedMapOf(
        "SKILL.md" to skill.originalSkillMd.toByteArray(),
        "skill.json" to requireNotNull(skill.originalSkillJson).toByteArray(),
    )

    private fun install(
        store: WorkspaceSkillStore,
        skill: WorkspaceSkillContract.ParsedSkill,
        at: Long,
    ): WorkspaceSkillStore.Installed {
        val packageFiles = files(skill)
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, packageFiles)
        val approval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        return store.install(skill, packageFiles, approval, approval.approvalToken, at)
    }

    private fun enable(
        store: WorkspaceSkillStore,
        installed: WorkspaceSkillStore.Installed,
        testedAt: Long,
        enabledAt: Long,
    ): WorkspaceSkillStore.Installed {
        val environment = WorkspaceSkillReadinessSurface.currentEnvironment(
            store.listVerified()
        )
        val report = WorkspaceSkillEnablement.test(installed, environment, testedAt)
        val request = WorkspaceSkillEnablement.enableRequest(installed, report)
        return store.enable(
            installed.entry.name,
            environment,
            request,
            request.approvalToken,
            enabledAt,
        )
    }

    @Test fun installedButDisabledDependencyCannotProduceEnableApproval() {
        val store = WorkspaceSkillStore(temp.newFolder("dependency-readiness"))
        install(store, skill("base"), 1L)
        val child = install(store, skill("child", listOf("base")), 2L)

        val environment = WorkspaceSkillReadinessSurface.currentEnvironment(
            store.listVerified()
        )
        val report = WorkspaceSkillEnablement.test(child, environment, 3L)

        assertEquals(WorkspaceSkillEnablement.Status.BLOCKED, report.status)
        assertTrue(report.checks.first { it.id == "dependencies" }.passed)
        assertTrue(!report.checks.first { it.id == "dependency-activation" }.passed)
        assertTrue(runCatching {
            WorkspaceSkillEnablement.enableRequest(child, report)
        }.isFailure)
    }

    @Test fun storeRejectsFalseEnvironmentThatClaimsDisabledDependencyEnabled() {
        val store = WorkspaceSkillStore(temp.newFolder("dependency-store-guard"))
        install(store, skill("base"), 1L)
        val child = install(store, skill("child", listOf("base")), 2L)

        val falseEnvironment = WorkspaceSkillEnablement.Environment(
            installedSkills = setOf("base", "child"),
            enabledSkills = setOf("base"),
        )
        val report = WorkspaceSkillEnablement.test(child, falseEnvironment, 3L)
        val request = WorkspaceSkillEnablement.enableRequest(child, report)

        assertTrue(runCatching {
            store.enable(
                "child",
                falseEnvironment,
                request,
                request.approvalToken,
                4L,
            )
        }.isFailure)
        assertEquals(
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
            store.load("child").entry.state,
        )
    }

    @Test fun dependencyActivationChangeMakesPriorApprovalUnusable() {
        val store = WorkspaceSkillStore(temp.newFolder("dependency-stale"))
        val base = install(store, skill("base"), 1L)
        val child = install(store, skill("child", listOf("base")), 2L)

        enable(store, base, testedAt = 3L, enabledAt = 4L)

        val approvedEnvironment = WorkspaceSkillReadinessSurface.currentEnvironment(
            store.listVerified()
        )
        val report = WorkspaceSkillEnablement.test(child, approvedEnvironment, 5L)
        val request = WorkspaceSkillEnablement.enableRequest(child, report)

        val enabledBase = store.load("base")
        val disable = WorkspaceSkillDisableApproval.prepare(enabledBase)
        store.disable(
            "base",
            disable.request,
            disable.request.approvalToken,
        )

        assertTrue(runCatching {
            store.enable(
                "child",
                approvedEnvironment,
                request,
                request.approvalToken,
                6L,
            )
        }.isFailure)
        assertEquals(
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
            store.load("child").entry.state,
        )
    }

    @Test fun childCanEnableAfterDependencyIsFreshlyEnabledAndReapproved() {
        val store = WorkspaceSkillStore(temp.newFolder("dependency-success"))
        val base = install(store, skill("base"), 1L)
        val child = install(store, skill("child", listOf("base")), 2L)

        enable(store, base, testedAt = 3L, enabledAt = 4L)

        val enabledChild = enable(store, child, testedAt = 5L, enabledAt = 6L)

        assertEquals(WorkspaceSkillCatalog.State.ENABLED, enabledChild.entry.state)
        assertEquals(
            setOf("base", "child"),
            WorkspaceSkillReadinessSurface.currentEnvironment(
                store.listVerified()
            ).enabledSkills,
        )
    }
}
