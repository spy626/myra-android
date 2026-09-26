package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillDependencyAuditTest {
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
        installedNames: Set<String>,
        enabledNames: Set<String>,
        at: Long,
    ): WorkspaceSkillStore.Installed {
        val environment = WorkspaceSkillEnablement.Environment(
            installedSkills = installedNames,
            enabledSkills = enabledNames,
        )
        val readiness = WorkspaceSkillEnablement.test(installed, environment, at)
        val request = WorkspaceSkillEnablement.enableRequest(installed, readiness)
        return store.enable(
            installed.entry.name,
            environment,
            request,
            request.approvalToken,
            at + 1L,
        )
    }

    @Test fun missingDependencyIsReportedWithoutMutation() {
        val store = WorkspaceSkillStore(temp.newFolder("audit-missing"))
        val child = install(store, skill("child", listOf("base")), 1L)
        val before = store.readCatalog()

        val audit = WorkspaceSkillDependencyAudit.analyze(store.listVerified())

        assertFalse(audit.healthy)
        assertEquals(1, audit.declaredDependencyCount)
        assertEquals(
            listOf("child" to "base"),
            audit.missingDependencies.map { it.skillName to it.dependencyName },
        )
        assertEquals(before, store.readCatalog())
        assertEquals(child.entry.packageSha256, store.load("child").entry.packageSha256)
    }

    @Test fun disabledDependencyIsAdvisoryForDisabledSkillButBreakForEnabledSkill() {
        val store = WorkspaceSkillStore(temp.newFolder("audit-disabled"))
        install(store, skill("base"), 1L)
        val child = install(store, skill("child", listOf("base")), 2L)

        val disabledAudit = WorkspaceSkillDependencyAudit.analyze(store.listVerified())
        assertTrue(disabledAudit.healthy)
        assertEquals(1, disabledAudit.disabledDependencyReferences.size)
        assertTrue(disabledAudit.enabledDependencyBreaks.isEmpty())

        // Model a legacy enabled child snapshot that was approved when base was enabled,
        // then audit it beside a currently disabled base. H20 prevents creating this state anew.
        val legacyEnvironment = WorkspaceSkillEnablement.Environment(
            installedSkills = setOf("base", "child"),
            enabledSkills = setOf("base"),
        )
        val legacyReport = WorkspaceSkillEnablement.test(child, legacyEnvironment, 3L)
        val legacyRequest = WorkspaceSkillEnablement.enableRequest(child, legacyReport)
        val legacyEnabledChild = child.copy(
            entry = WorkspaceSkillEnablement.enabledEntry(
                child,
                legacyEnvironment,
                legacyRequest,
                legacyRequest.approvalToken,
                4L,
            )
        )

        val enabledAudit = WorkspaceSkillDependencyAudit.analyze(
            listOf(store.load("base"), legacyEnabledChild)
        )
        assertFalse(enabledAudit.healthy)
        assertEquals(
            listOf("child" to "base"),
            enabledAudit.enabledDependencyBreaks.map { it.skillName to it.dependencyName },
        )
    }

    @Test fun dependencyCycleIsReportedDeterministicallyAndDoesNotMutate() {
        val store = WorkspaceSkillStore(temp.newFolder("audit-cycle"))
        install(store, skill("alpha", listOf("beta")), 1L)
        install(store, skill("beta", listOf("gamma")), 2L)
        install(store, skill("gamma", listOf("alpha")), 3L)
        val before = store.readCatalog()

        val audit = WorkspaceSkillDependencyAudit.analyze(store.listVerified())

        assertFalse(audit.healthy)
        assertEquals(listOf(listOf("alpha", "beta", "gamma")), audit.cycles)
        assertEquals(before, store.readCatalog())
    }

    @Test fun healthyEnabledDependencyChainPasses() {
        val store = WorkspaceSkillStore(temp.newFolder("audit-healthy"))
        val base = install(store, skill("base"), 1L)
        install(store, skill("child", listOf("base")), 2L)

        enable(
            store,
            base,
            installedNames = setOf("base", "child"),
            enabledNames = emptySet(),
            at = 3L,
        )
        val child = store.load("child")
        enable(
            store,
            child,
            installedNames = setOf("base", "child"),
            enabledNames = setOf("base"),
            at = 5L,
        )

        val audit = WorkspaceSkillDependencyAudit.analyze(store.listVerified())

        assertTrue(audit.healthy)
        assertEquals(2, audit.installedSkillCount)
        assertEquals(1, audit.declaredDependencyCount)
        assertTrue(audit.missingDependencies.isEmpty())
        assertTrue(audit.disabledDependencyReferences.isEmpty())
        assertTrue(audit.enabledDependencyBreaks.isEmpty())
        assertTrue(audit.cycles.isEmpty())
    }

    @Test fun duplicateInstalledNamesAreSurfacedAsAmbiguousInput() {
        val store = WorkspaceSkillStore(temp.newFolder("audit-duplicates"))
        val one = install(store, skill("base"), 1L)

        val audit = WorkspaceSkillDependencyAudit.analyze(listOf(one, one))

        assertFalse(audit.healthy)
        assertEquals(listOf("base"), audit.duplicateSkillNames)
    }
}
