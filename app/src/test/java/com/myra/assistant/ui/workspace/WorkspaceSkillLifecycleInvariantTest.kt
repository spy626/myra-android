package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * H24 cross-lifecycle regression: existing skill authorities must compose without bypassing one
 * another. This test owns no production policy and performs only already-approved explicit actions.
 */
class WorkspaceSkillLifecycleInvariantTest {
    @get:Rule val temp = TemporaryFolder()

    private fun skill(
        name: String,
        description: String,
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
description: $description
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
        val environment = WorkspaceSkillReadinessSurface.currentEnvironment(store.listVerified())
        val report = WorkspaceSkillEnablement.test(installed, environment, testedAt)
        assertEquals(WorkspaceSkillEnablement.Status.PASS, report.status)
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
        val approval = WorkspaceSkillDisableApproval.prepare(installed)
        return store.disable(
            installed.entry.name,
            approval.request,
            approval.request.approvalToken,
        )
    }

    private fun candidate(
        skill: WorkspaceSkillContract.ParsedSkill,
    ): WorkspaceSkillUpdatePreview.Candidate {
        val packageFiles = files(skill)
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, packageFiles)
        return WorkspaceSkillUpdatePreview.Candidate(
            skill = skill,
            snapshot = snapshot,
            permissionSha256 =
                WorkspaceSkillCatalog.approvalRequest(skill, snapshot).permissionSha256,
            packageFiles = packageFiles.mapValues { it.value.copyOf() },
        )
    }

    @Test fun completeLifecyclePreservesAuthorityDependencyAndRetentionInvariants() {
        val root = temp.newFolder("full-lifecycle")
        val store = WorkspaceSkillStore(root)

        val baseV1 = install(store, skill("base", "Base V1."), 1L)
        val childV1 = install(
            store,
            skill("child", "Child V1.", dependencies = listOf("base")),
            2L,
        )
        assertTrue(WorkspaceSkillDependencyAudit.analyze(store.listVerified()).healthy)

        val enabledBaseV1 = enable(store, baseV1, 3L, 4L)
        val enabledChildV1 = enable(store, childV1, 5L, 6L)
        assertTrue(WorkspaceSkillDependencyAudit.analyze(store.listVerified()).healthy)

        val childProjectionV1 = requireNotNull(
            WorkspaceSkillUserEntry.prepare(
                store,
                "project-1",
                "turn-1",
                "/skill child verify lifecycle",
            )
        ).projection
        WorkspaceSkillInvocationFreshness.requireCurrent(store, childProjectionV1)

        val baseDisableApproval = WorkspaceSkillDisableApproval.prepare(enabledBaseV1)
        assertTrue(runCatching {
            store.disable(
                "base",
                baseDisableApproval.request,
                baseDisableApproval.request.approvalToken,
            )
        }.isFailure)

        val baseV2Candidate = candidate(skill("base", "Base V2."))
        val blockedUpdateApproval =
            WorkspaceSkillApprovedUpdate.prepare(enabledBaseV1, baseV2Candidate)
        assertTrue(runCatching {
            store.updateApprovedPackage(
                "base",
                baseV2Candidate,
                blockedUpdateApproval.request,
                blockedUpdateApproval.request.approvalToken,
                7L,
            )
        }.isFailure)
        assertFalse(
            File(root, "packages/" + baseV2Candidate.snapshot.packageSha256).exists()
        )

        val disabledChildV1 = disable(store, enabledChildV1)
        val freshUpdateApproval =
            WorkspaceSkillApprovedUpdate.prepare(store.load("base"), baseV2Candidate)
        val baseV2 = store.updateApprovedPackage(
            "base",
            baseV2Candidate,
            freshUpdateApproval.request,
            freshUpdateApproval.request.approvalToken,
            8L,
        )
        assertEquals(
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
            baseV2.entry.state,
        )
        assertEquals(
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
            disabledChildV1.entry.state,
        )
        assertTrue(runCatching {
            WorkspaceSkillInvocationFreshness.requireCurrent(store, childProjectionV1)
        }.isFailure)

        val enabledBaseV2 = enable(store, baseV2, 9L, 10L)
        val enabledChildV1Again = enable(store, store.load("child"), 11L, 12L)
        assertTrue(WorkspaceSkillDependencyAudit.analyze(store.listVerified()).healthy)

        val rollbackV1 = store.loadRollback("base")
        val blockedRollback =
            WorkspaceSkillApprovedRollback.prepare(enabledBaseV2, rollbackV1)
        assertTrue(runCatching {
            store.rollbackApproved(
                "base",
                blockedRollback.request,
                blockedRollback.request.approvalToken,
                13L,
            )
        }.isFailure)

        disable(store, enabledChildV1Again)
        val rollbackApproval =
            WorkspaceSkillApprovedRollback.prepare(store.load("base"), store.loadRollback("base"))
        val restoredBaseV1 = store.rollbackApproved(
            "base",
            rollbackApproval.request,
            rollbackApproval.request.approvalToken,
            14L,
        )
        assertEquals(baseV1.entry.packageSha256, restoredBaseV1.entry.packageSha256)
        assertEquals(
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
            restoredBaseV1.entry.state,
        )

        val enabledRestoredBase = enable(store, restoredBaseV1, 15L, 16L)
        val enabledChildFinal = enable(store, store.load("child"), 17L, 18L)
        val finalProjection = requireNotNull(
            WorkspaceSkillUserEntry.prepare(
                store,
                "project-1",
                "turn-2",
                "/skill child verify final lifecycle",
            )
        ).projection
        WorkspaceSkillInvocationFreshness.requireCurrent(store, finalProjection)

        disable(store, enabledChildFinal)
        val disabledBase = disable(store, enabledRestoredBase)
        assertTrue(runCatching {
            WorkspaceSkillInvocationFreshness.requireCurrent(store, finalProjection)
        }.isFailure)

        val baseUninstallWhileChildInstalled =
            WorkspaceSkillApprovedUninstall.prepare(disabledBase, store.loadRollback("base"))
        assertTrue(runCatching {
            store.uninstallApproved(
                "base",
                baseUninstallWhileChildInstalled.request,
                baseUninstallWhileChildInstalled.request.approvalToken,
            )
        }.isFailure)

        val child = store.load("child")
        val childUninstall = WorkspaceSkillApprovedUninstall.prepare(child, null)
        store.uninstallApproved(
            "child",
            childUninstall.request,
            childUninstall.request.approvalToken,
        )

        val base = store.load("base")
        val baseRollback = store.loadRollback("base")
        val baseUninstall = WorkspaceSkillApprovedUninstall.prepare(base, baseRollback)
        store.uninstallApproved(
            "base",
            baseUninstall.request,
            baseUninstall.request.approvalToken,
        )
        assertTrue(store.readCatalog().entries.isEmpty())

        val expectedPackages = listOf(
            baseV1.entry.packageSha256,
            baseV2.entry.packageSha256,
            childV1.entry.packageSha256,
        ).distinct().sorted()
        val audit = store.retentionAudit()
        assertTrue(audit.currentPackageSha256.isEmpty())
        assertTrue(audit.rollbackPackageSha256.isEmpty())
        assertEquals(expectedPackages, audit.reclaimablePackageSha256)

        val deleted = store.cleanupAuditedPackages(audit)
        assertEquals(expectedPackages, deleted.sorted())
        assertTrue(store.retentionAudit().reclaimablePackageSha256.isEmpty())
        expectedPackages.forEach { hash ->
            assertFalse(File(root, "packages/" + hash).exists())
        }
    }
}
