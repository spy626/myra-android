package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillInvocationFreshnessTest {
    @get:Rule val temp = TemporaryFolder()

    private fun skill(description: String): WorkspaceSkillContract.ParsedSkill =
        WorkspaceSkillContract.parse(
            """---
name: review-code
description: $description
---
## Verification
Check exact local evidence.
""",
            """{"allowedTools":[]}""",
            packagePaths = listOf("SKILL.md", "skill.json"),
        )

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
        val request = WorkspaceSkillEnablement.enableRequest(installed, report)
        return store.enable(
            installed.entry.name,
            environment,
            request,
            request.approvalToken,
            enabledAt,
        )
    }

    private fun projection(
        store: WorkspaceSkillStore,
        projectId: String = "project-1",
        turnId: String = "turn-1",
    ): WorkspaceSkillInvocation.Projection =
        requireNotNull(
            WorkspaceSkillUserEntry.prepare(
                store,
                projectId,
                turnId,
                "/skill review-code verify this",
            )
        ).projection

    @Test fun exactCurrentActivationRemainsFresh() {
        val store = WorkspaceSkillStore(temp.newFolder("fresh"))
        val installed = install(store, skill("V1."), 1L)
        val enabled = enable(store, installed, 2L, 3L)
        val projection = projection(store)

        val current = WorkspaceSkillInvocationFreshness.requireCurrent(store, projection)

        assertEquals(enabled.entry.packageSha256, current.entry.packageSha256)
        assertEquals(enabled.entry.enableBindingSha256, projection.activationBindingSha256)
    }

    @Test fun disabledSkillMakesInFlightProjectionStale() {
        val store = WorkspaceSkillStore(temp.newFolder("disabled"))
        val installed = install(store, skill("V1."), 1L)
        val enabled = enable(store, installed, 2L, 3L)
        val projection = projection(store)

        val disable = WorkspaceSkillDisableApproval.prepare(enabled)
        store.disable(
            enabled.entry.name,
            disable.request,
            disable.request.approvalToken,
        )

        assertTrue(runCatching {
            WorkspaceSkillInvocationFreshness.requireCurrent(store, projection)
        }.isFailure)
    }

    @Test fun disableAndReenableSamePackageStillRejectsOldActivationBinding() {
        val store = WorkspaceSkillStore(temp.newFolder("reactivated"))
        val installed = install(store, skill("V1."), 1L)
        val firstEnabled = enable(store, installed, 2L, 3L)
        val projection = projection(store)

        val disable = WorkspaceSkillDisableApproval.prepare(firstEnabled)
        val disabled = store.disable(
            firstEnabled.entry.name,
            disable.request,
            disable.request.approvalToken,
        )
        val secondEnabled = enable(store, disabled, 4L, 5L)

        assertTrue(firstEnabled.entry.enableBindingSha256 != secondEnabled.entry.enableBindingSha256)
        assertTrue(runCatching {
            WorkspaceSkillInvocationFreshness.requireCurrent(store, projection)
        }.isFailure)
    }

    @Test fun packageTransitionMakesInFlightProjectionStale() {
        val store = WorkspaceSkillStore(temp.newFolder("updated"))
        val installed = install(store, skill("V1."), 1L)
        val enabled = enable(store, installed, 2L, 3L)
        val projection = projection(store)

        val next = skill("V2.")
        val packageFiles = files(next)
        val snapshot = WorkspaceSkillCatalog.snapshot(next, packageFiles)
        val candidate = WorkspaceSkillUpdatePreview.Candidate(
            skill = next,
            snapshot = snapshot,
            permissionSha256 =
                WorkspaceSkillCatalog.approvalRequest(next, snapshot).permissionSha256,
            packageFiles = packageFiles.mapValues { it.value.copyOf() },
        )
        val prepared = WorkspaceSkillApprovedUpdate.prepare(enabled, candidate)
        store.updateApprovedPackage(
            enabled.entry.name,
            candidate,
            prepared.request,
            prepared.request.approvalToken,
            4L,
        )

        assertTrue(runCatching {
            WorkspaceSkillInvocationFreshness.requireCurrent(store, projection)
        }.isFailure)
    }
}
