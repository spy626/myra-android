package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSkillUninstallDependencyGuardTest {
    @get:Rule val temp = TemporaryFolder()

    private fun skill(
        name: String,
        dependencySkills: List<String> = emptyList(),
    ): WorkspaceSkillContract.ParsedSkill {
        val json = buildString {
            append("{\"allowedTools\":[]")
            if (dependencySkills.isNotEmpty()) {
                append(",\"dependencySkills\":[")
                append(dependencySkills.joinToString(",") { "\"" + it + "\"" })
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
        return store.install(
            skill, packageFiles, approval, approval.approvalToken, at)
    }

    @Test fun installedDependentBlocksApprovedUninstallWithoutCascadeOrDeletion() {
        val root = temp.newFolder("dependency-block")
        val store = WorkspaceSkillStore(root)
        val base = install(store, skill("base-review"), 1L)
        val child = install(
            store,
            skill("child-review", dependencySkills = listOf("base-review")),
            2L,
        )

        val impact = WorkspaceSkillUninstallDependencyGuard.analyze(
            "base-review",
            store.listVerified(),
        )
        val preview = WorkspaceSkillUninstallPreview.from(base, null, impact.dependents)
        val prepared = WorkspaceSkillApprovedUninstall.prepare(base, null)

        assertTrue(impact.blocked)
        assertEquals(listOf("child-review"), impact.dependents.map { it.skillName })
        assertEquals("UNINSTALL BLOCKED · DEPENDENTS", preview.status)
        assertTrue(preview.rows.any {
            it.label == "Dependent skills" && it.value.contains("child-review")
        })

        assertTrue(runCatching {
            store.uninstallApproved(
                "base-review",
                prepared.request,
                prepared.request.approvalToken,
            )
        }.isFailure)

        assertEquals(2, store.readCatalog().entries.size)
        assertEquals(base.entry.packageSha256, store.load("base-review").entry.packageSha256)
        assertEquals(child.entry.packageSha256, store.load("child-review").entry.packageSha256)
        assertTrue(File(root, "packages/" + base.entry.packageSha256).isDirectory)
        assertTrue(File(root, "packages/" + child.entry.packageSha256).isDirectory)
    }

    @Test fun enabledDependentAlsoBlocksAndNoAutomaticDisableOccurs() {
        val store = WorkspaceSkillStore(temp.newFolder("enabled-dependent"))
        val base = install(store, skill("base-review"), 1L)
        var child = install(
            store,
            skill("child-review", dependencySkills = listOf("base-review")),
            2L,
        )
        val baseEnvironment = WorkspaceSkillReadinessSurface.currentEnvironment(
            store.listVerified()
        )
        val baseReadiness = WorkspaceSkillEnablement.test(base, baseEnvironment, 3L)
        val baseEnable = WorkspaceSkillEnablement.enableRequest(base, baseReadiness)
        val enabledBase = store.enable(
            "base-review",
            baseEnvironment,
            baseEnable,
            baseEnable.approvalToken,
            4L,
        )

        val environment = WorkspaceSkillReadinessSurface.currentEnvironment(
            store.listVerified()
        )
        val readiness = WorkspaceSkillEnablement.test(child, environment, 5L)
        val enable = WorkspaceSkillEnablement.enableRequest(child, readiness)
        child = store.enable(
            "child-review",
            environment,
            enable,
            enable.approvalToken,
            6L,
        )
        val prepared = WorkspaceSkillApprovedUninstall.prepare(enabledBase, null)

        assertTrue(runCatching {
            store.uninstallApproved(
                "base-review",
                prepared.request,
                prepared.request.approvalToken,
            )
        }.isFailure)
        assertEquals(WorkspaceSkillCatalog.State.ENABLED, store.load("child-review").entry.state)
        assertEquals(child.entry.enableBindingSha256, store.load("child-review").entry.enableBindingSha256)
        assertEquals(base.entry.packageSha256, store.load("base-review").entry.packageSha256)
    }

    @Test fun targetCanBeUninstalledAfterDependentIsExplicitlyRemovedFirst() {
        val store = WorkspaceSkillStore(temp.newFolder("dependency-explicit-order"))
        install(store, skill("base-review"), 1L)
        val child = install(
            store,
            skill("child-review", dependencySkills = listOf("base-review")),
            2L,
        )

        val childApproval = WorkspaceSkillApprovedUninstall.prepare(child, null)
        store.uninstallApproved(
            "child-review",
            childApproval.request,
            childApproval.request.approvalToken,
        )
        assertFalse(store.readCatalog().entries.any { it.name == "child-review" })

        val impact = WorkspaceSkillUninstallDependencyGuard.analyze(
            "base-review",
            store.listVerified(),
        )
        assertFalse(impact.blocked)

        val base = store.load("base-review")
        val baseApproval = WorkspaceSkillApprovedUninstall.prepare(base, null)
        store.uninstallApproved(
            "base-review",
            baseApproval.request,
            baseApproval.request.approvalToken,
        )
        assertTrue(store.readCatalog().entries.isEmpty())
    }
}
