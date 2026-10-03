package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSkillPackageTransitionDependencyGuardTest {
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

    private fun promotion(
        base: WorkspaceSkillContract.ParsedSkill,
        nextDescription: String,
    ): WorkspaceSkillOverlayPromotion.Promotion {
        val ref = "verify:" + base.name + ":transition"
        val overlay = WorkspaceSkillOverlay.Overlay(
            baseContentSha256 = base.contentSha256,
            descriptionOverride = nextDescription,
            evidenceRefs = listOf(ref),
            createdAtMs = 20L,
        )
        val evidence = WorkspaceSkillImprovementEvidence.Record(
            ref = ref,
            skillName = base.name,
            baseContentSha256 = base.contentSha256,
            kind = WorkspaceSkillImprovementEvidence.Kind.DETERMINISTIC_VERIFICATION,
            signal = WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
            capturedAtMs = 20L,
            sourceRevision = "h22",
        )
        return WorkspaceSkillOverlayPromotion.evaluate(base, overlay, listOf(evidence))
    }

    @Test fun enabledDependentMakesPreviouslyPreparedApprovedUpdateFailBeforePackageWrite() {
        val root = temp.newFolder("approved-update-block")
        val store = WorkspaceSkillStore(root)
        val base = install(store, skill("base", "Base V1."), 1L)
        val child = install(store, skill("child", "Child.", listOf("base")), 2L)
        val enabledBase = enable(store, base, 3L, 4L)

        val next = skill("base", "Base V2.")
        val nextCandidate = candidate(next)
        val prepared = WorkspaceSkillApprovedUpdate.prepare(enabledBase, nextCandidate)

        enable(store, child, 5L, 6L)

        assertTrue(runCatching {
            store.updateApprovedPackage(
                "base",
                nextCandidate,
                prepared.request,
                prepared.request.approvalToken,
                7L,
            )
        }.isFailure)
        assertEquals(enabledBase.entry.packageSha256, store.load("base").entry.packageSha256)
        assertEquals(WorkspaceSkillCatalog.State.ENABLED, store.load("base").entry.state)
        assertEquals(WorkspaceSkillCatalog.State.ENABLED, store.load("child").entry.state)
        assertFalse(File(root, "packages/" + nextCandidate.snapshot.packageSha256).exists())
    }

    @Test fun enabledDependentBlocksEvidenceBackedUpdateBeforePackageWrite() {
        val root = temp.newFolder("promoted-update-block")
        val store = WorkspaceSkillStore(root)
        val base = install(store, skill("base", "Base V1."), 1L)
        val child = install(store, skill("child", "Child.", listOf("base")), 2L)
        val enabledBase = enable(store, base, 3L, 4L)

        val next = skill("base", "Base V2.")
        val nextFiles = files(next)
        val snapshot = WorkspaceSkillCatalog.snapshot(next, nextFiles)
        val promo = promotion(enabledBase.skill, next.description)
        val request = WorkspaceSkillUpdate.request(
            enabledBase,
            next,
            snapshot,
            promo,
        )

        enable(store, child, 5L, 6L)

        assertTrue(runCatching {
            store.update(
                "base",
                next,
                nextFiles,
                promo,
                request,
                request.approvalToken,
                7L,
            )
        }.isFailure)
        assertEquals(enabledBase.entry.packageSha256, store.load("base").entry.packageSha256)
        assertEquals(WorkspaceSkillCatalog.State.ENABLED, store.load("child").entry.state)
        assertFalse(File(root, "packages/" + snapshot.packageSha256).exists())
    }

    @Test fun enabledDependentMakesPreviouslyPreparedRollbackFailClosed() {
        val root = temp.newFolder("rollback-block")
        val store = WorkspaceSkillStore(root)
        val first = install(store, skill("base", "Base V1."), 1L)

        val v2 = candidate(skill("base", "Base V2."))
        val updateApproval = WorkspaceSkillApprovedUpdate.prepare(first, v2)
        val second = store.updateApprovedPackage(
            "base",
            v2,
            updateApproval.request,
            updateApproval.request.approvalToken,
            2L,
        )
        val enabledBase = enable(store, second, 3L, 4L)
        val child = install(store, skill("child", "Child.", listOf("base")), 5L)

        val rollback = store.loadRollback("base")
        val prepared = WorkspaceSkillApprovedRollback.prepare(enabledBase, rollback)

        enable(store, child, 6L, 7L)

        assertTrue(runCatching {
            store.rollbackApproved(
                "base",
                prepared.request,
                prepared.request.approvalToken,
                8L,
            )
        }.isFailure)
        assertEquals(enabledBase.entry.packageSha256, store.load("base").entry.packageSha256)
        assertEquals(WorkspaceSkillCatalog.State.ENABLED, store.load("base").entry.state)
        assertEquals(WorkspaceSkillCatalog.State.ENABLED, store.load("child").entry.state)
    }

    @Test fun rollbackCanProceedAfterDependentIsExplicitlyDisabled() {
        val store = WorkspaceSkillStore(temp.newFolder("rollback-explicit-order"))
        val first = install(store, skill("base", "Base V1."), 1L)
        val v2 = candidate(skill("base", "Base V2."))
        val updateApproval = WorkspaceSkillApprovedUpdate.prepare(first, v2)
        val second = store.updateApprovedPackage(
            "base",
            v2,
            updateApproval.request,
            updateApproval.request.approvalToken,
            2L,
        )
        val enabledBase = enable(store, second, 3L, 4L)
        val child = install(store, skill("child", "Child.", listOf("base")), 5L)
        val enabledChild = enable(store, child, 6L, 7L)

        disable(store, enabledChild)

        val rollback = store.loadRollback("base")
        val prepared = WorkspaceSkillApprovedRollback.prepare(enabledBase, rollback)
        val restored = store.rollbackApproved(
            "base",
            prepared.request,
            prepared.request.approvalToken,
            8L,
        )

        assertEquals(first.entry.packageSha256, restored.entry.packageSha256)
        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED, restored.entry.state)
        assertEquals(
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
            store.load("child").entry.state,
        )
    }
}
