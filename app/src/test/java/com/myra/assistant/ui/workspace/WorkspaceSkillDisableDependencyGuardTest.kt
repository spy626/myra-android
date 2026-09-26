package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillDisableDependencyGuardTest {
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

    private fun disable(
        store: WorkspaceSkillStore,
        installed: WorkspaceSkillStore.Installed,
    ): WorkspaceSkillStore.Installed {
        val prepared = WorkspaceSkillDisableApproval.prepare(installed)
        return store.disable(
            installed.entry.name,
            prepared.request,
            prepared.request.approvalToken,
        )
    }

    @Test fun enabledDependentBlocksDependencyDisableWithoutCascade() {
        val store = WorkspaceSkillStore(temp.newFolder("disable-block"))
        val base = install(store, skill("base"), 1L)
        val child = install(store, skill("child", listOf("base")), 2L)
        val enabledBase = enable(store, base, 3L, 4L)
        val enabledChild = enable(store, child, 5L, 6L)

        val impact = WorkspaceSkillDisableDependencyGuard.analyze(
            "base",
            store.listVerified(),
        )
        val prepared = WorkspaceSkillDisableApproval.prepare(enabledBase)

        assertTrue(impact.blocked)
        assertEquals(listOf("child"), impact.enabledDependents.map { it.skillName })
        assertTrue(runCatching {
            store.disable("base", prepared.request, prepared.request.approvalToken)
        }.isFailure)

        assertEquals(WorkspaceSkillCatalog.State.ENABLED, store.load("base").entry.state)
        assertEquals(WorkspaceSkillCatalog.State.ENABLED, store.load("child").entry.state)
        assertEquals(
            enabledChild.entry.enableBindingSha256,
            store.load("child").entry.enableBindingSha256,
        )
    }

    @Test fun disabledDependentDoesNotBlockDependencyDisable() {
        val store = WorkspaceSkillStore(temp.newFolder("disable-disabled-dependent"))
        val base = install(store, skill("base"), 1L)
        install(store, skill("child", listOf("base")), 2L)
        val enabledBase = enable(store, base, 3L, 4L)

        val impact = WorkspaceSkillDisableDependencyGuard.analyze(
            "base",
            store.listVerified(),
        )
        assertFalse(impact.blocked)

        val disabledBase = disable(store, enabledBase)
        assertEquals(
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
            disabledBase.entry.state,
        )
        assertEquals(
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
            store.load("child").entry.state,
        )
    }

    @Test fun targetCanDisableAfterDependentIsExplicitlyDisabledFirst() {
        val store = WorkspaceSkillStore(temp.newFolder("disable-explicit-order"))
        val base = install(store, skill("base"), 1L)
        val child = install(store, skill("child", listOf("base")), 2L)
        val enabledBase = enable(store, base, 3L, 4L)
        val enabledChild = enable(store, child, 5L, 6L)

        disable(store, enabledChild)
        val impact = WorkspaceSkillDisableDependencyGuard.analyze(
            "base",
            store.listVerified(),
        )
        assertFalse(impact.blocked)

        val disabledBase = disable(store, enabledBase)
        assertEquals(
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
            disabledBase.entry.state,
        )
    }

    @Test fun newlyEnabledDependentMakesOldDisableApprovalFailClosed() {
        val store = WorkspaceSkillStore(temp.newFolder("disable-stale"))
        val base = install(store, skill("base"), 1L)
        val child = install(store, skill("child", listOf("base")), 2L)
        val enabledBase = enable(store, base, 3L, 4L)
        val oldApproval = WorkspaceSkillDisableApproval.prepare(enabledBase)

        enable(store, child, 5L, 6L)

        assertTrue(runCatching {
            store.disable(
                "base",
                oldApproval.request,
                oldApproval.request.approvalToken,
            )
        }.isFailure)
        assertEquals(WorkspaceSkillCatalog.State.ENABLED, store.load("base").entry.state)
        assertEquals(WorkspaceSkillCatalog.State.ENABLED, store.load("child").entry.state)
    }
}
