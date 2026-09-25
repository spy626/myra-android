package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSkillStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun parsed(
        description: String = "Review code safely.",
    ): WorkspaceSkillContract.ParsedSkill {
        val md = """---
name: review-code
description: $description
---
## Verification
Require deterministic evidence.
"""
        val json = """{"allowedTools":["read_file"],"sourceSharing":"BOUNDED"}"""
        return WorkspaceSkillContract.parse(
            md, json, packagePaths = listOf("SKILL.md", "skill.json", "references/checklist.md"))
    }

    private fun files(skill: WorkspaceSkillContract.ParsedSkill): Map<String, ByteArray> =
        linkedMapOf(
            "SKILL.md" to skill.originalSkillMd.toByteArray(),
            "skill.json" to requireNotNull(skill.originalSkillJson).toByteArray(),
            "references/checklist.md" to "Check current source revision.".toByteArray(),
        )

    private fun environment() = WorkspaceSkillEnablement.Environment(
        availableTools = setOf("read_file"),
        boundedSourceGateAvailable = true,
    )

    private fun install(
        store: WorkspaceSkillStore,
        skill: WorkspaceSkillContract.ParsedSkill,
        at: Long = 10L,
    ): WorkspaceSkillStore.Installed {
        val bytes = files(skill)
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, bytes)
        val approval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        return store.install(skill, bytes, approval, approval.approvalToken, at)
    }

    @Test fun approvedSkillPersistsReopensAndRemainsDisabled() {
        val root = temp.newFolder("skills")
        val store = WorkspaceSkillStore(root)
        val installed = install(store, parsed())
        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED, installed.entry.state)

        val reopened = WorkspaceSkillStore(root).load("review-code")
        assertEquals(installed.entry, reopened.entry)
        assertEquals(installed.skill.body, reopened.skill.body)
        assertEquals(installed.snapshot.packageSha256, reopened.snapshot.packageSha256)
        assertEquals(1, WorkspaceSkillStore(root).listVerified().size)
    }

    @Test fun wrongApprovalWritesNoCatalogOrPackage() {
        val root = temp.newFolder("unapproved")
        val store = WorkspaceSkillStore(root)
        val skill = parsed()
        val bytes = files(skill)
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, bytes)
        val approval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)

        assertTrue(runCatching {
            store.install(skill, bytes, approval, "wrong-token", 1L)
        }.isFailure)
        assertTrue(store.readCatalog().entries.isEmpty())
        assertTrue(File(root, "packages").listFiles().orEmpty().isEmpty())
    }

    @Test fun tamperedPackageFailsClosedOnLoad() {
        val root = temp.newFolder("tampered")
        val store = WorkspaceSkillStore(root)
        val installed = install(store, parsed())
        val skillFile = File(
            root,
            "packages/${installed.entry.packageSha256}/SKILL.md",
        )
        skillFile.appendText("\nTampered")
        assertTrue(runCatching {
            WorkspaceSkillStore(root).load("review-code")
        }.isFailure)
    }

    @Test fun sameNameDifferentContentDoesNotOverwriteInstalledPackage() {
        val root = temp.newFolder("collision")
        val store = WorkspaceSkillStore(root)
        val first = install(store, parsed(), 10L)
        val changed = parsed(description = "Different reviewed skill.")
        val bytes = files(changed)
        val snapshot = WorkspaceSkillCatalog.snapshot(changed, bytes)
        val approval = WorkspaceSkillCatalog.approvalRequest(changed, snapshot)

        assertTrue(runCatching {
            store.install(changed, bytes, approval, approval.approvalToken, 20L)
        }.isFailure)

        val reopened = WorkspaceSkillStore(root).load("review-code")
        assertEquals(first.entry.packageSha256, reopened.entry.packageSha256)
        assertEquals("Review code safely.", reopened.entry.description)
    }

    @Test fun corruptedCatalogIdentityFailsClosed() {
        val root = temp.newFolder("catalog-corrupt")
        val store = WorkspaceSkillStore(root)
        install(store, parsed())
        val catalog = File(root, "catalog.json")
        catalog.writeText(catalog.readText().replace(
            "\"name\":\"review-code\"",
            "\"name\":\"../escape\"",
        ))
        assertTrue(runCatching { WorkspaceSkillStore(root).readCatalog() }.isFailure)
    }

    @Test fun exactReinstallIsIdempotentAndDoesNotChangeInstallTime() {
        val root = temp.newFolder("idempotent")
        val store = WorkspaceSkillStore(root)
        val skill = parsed()
        val first = install(store, skill, 10L)
        val second = install(store, skill, 99L)
        assertEquals(first.entry, second.entry)
        assertEquals(10L, second.entry.installedAtMs)
    }

    @Test fun readinessApprovedSkillCanEnablePersistAndDisableWithoutChangingPackage() {
        val root = temp.newFolder("enable-disable")
        val store = WorkspaceSkillStore(root)
        val installed = install(store, parsed())
        val report = WorkspaceSkillEnablement.test(installed, environment(), 20L)
        val request = WorkspaceSkillEnablement.enableRequest(installed, report)

        val enabled = store.enable(
            "review-code", environment(), request, request.approvalToken, 30L)
        assertEquals(WorkspaceSkillCatalog.State.ENABLED, enabled.entry.state)
        assertEquals(30L, enabled.entry.enabledAtMs ?: -1L)
        assertEquals(request.readinessSha256, enabled.entry.enableReadinessSha256)
        assertEquals(request.environmentSha256, enabled.entry.enableEnvironmentSha256)
        assertEquals(installed.snapshot.packageSha256, enabled.snapshot.packageSha256)

        val reopened = WorkspaceSkillStore(root).load("review-code")
        assertEquals(enabled.entry, reopened.entry)

        val disabled = WorkspaceSkillStore(root).disable("review-code")
        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED, disabled.entry.state)
        assertNull(disabled.entry.enabledAtMs)
        assertNull(disabled.entry.enableReadinessSha256)
        assertNull(disabled.entry.enableEnvironmentSha256)
        assertNull(disabled.entry.enableBindingSha256)
        assertEquals(installed.snapshot.packageSha256, disabled.snapshot.packageSha256)
    }

    @Test fun wrongEnableTokenOrChangedEnvironmentLeavesSkillDisabled() {
        val root = temp.newFolder("enable-reject")
        val store = WorkspaceSkillStore(root)
        val installed = install(store, parsed())
        val report = WorkspaceSkillEnablement.test(installed, environment(), 20L)
        val request = WorkspaceSkillEnablement.enableRequest(installed, report)

        assertTrue(runCatching {
            store.enable("review-code", environment(), request, "wrong", 30L)
        }.isFailure)
        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
            store.load("review-code").entry.state)

        assertTrue(runCatching {
            store.enable(
                "review-code",
                WorkspaceSkillEnablement.Environment(availableTools = setOf("read_file")),
                request,
                request.approvalToken,
                30L,
            )
        }.isFailure)
        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
            store.load("review-code").entry.state)
    }

    @Test fun legacyDisabledCatalogRemainsReadableAndMigratesOnEnable() {
        val root = temp.newFolder("legacy")
        val store = WorkspaceSkillStore(root)
        val installed = install(store, parsed())
        val catalog = File(root, "catalog.json")
        catalog.writeText(catalog.readText().replace(
            "\"schemaVersion\":2",
            "\"schemaVersion\":1",
        ))

        val legacy = WorkspaceSkillStore(root).load("review-code")
        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED, legacy.entry.state)

        val report = WorkspaceSkillEnablement.test(legacy, environment(), 20L)
        val request = WorkspaceSkillEnablement.enableRequest(legacy, report)
        WorkspaceSkillStore(root).enable(
            "review-code", environment(), request, request.approvalToken, 30L)
        assertTrue(catalog.readText().contains("\"schemaVersion\":2"))
    }

    @Test fun corruptedEnabledActivationMetadataFailsClosedOnLoad() {
        val root = temp.newFolder("activation-corrupt")
        val store = WorkspaceSkillStore(root)
        val installed = install(store, parsed())
        val report = WorkspaceSkillEnablement.test(installed, environment(), 20L)
        val request = WorkspaceSkillEnablement.enableRequest(installed, report)
        val enabled = store.enable(
            "review-code", environment(), request, request.approvalToken, 30L)

        val catalog = File(root, "catalog.json")
        catalog.writeText(catalog.readText().replace(
            enabled.entry.enableBindingSha256!!,
            "0".repeat(64),
        ))
        assertTrue(runCatching {
            WorkspaceSkillStore(root).load("review-code")
        }.isFailure)
    }



    @Test fun approvedUpdateSwitchesCatalogDisabledAndPreservesOldPackageBytes() {
        val root = temp.newFolder("skill-update")
        val store = WorkspaceSkillStore(root)
        val old = parsed()
        val current = install(store, old, 10L)

        val changed = parsed(description = "Improved reviewed skill.")
        val changedFiles = files(changed)
        val changedSnapshot = WorkspaceSkillCatalog.snapshot(changed, changedFiles)
        val ref = "verify:review-code:update"
        val overlay = WorkspaceSkillOverlay.Overlay(
            baseContentSha256 = old.contentSha256,
            descriptionOverride = changed.description,
            evidenceRefs = listOf(ref),
            createdAtMs = 15L,
        )
        val evidence = WorkspaceSkillImprovementEvidence.Record(
            ref = ref,
            skillName = old.name,
            baseContentSha256 = old.contentSha256,
            kind = WorkspaceSkillImprovementEvidence.Kind.DETERMINISTIC_VERIFICATION,
            signal = WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
            capturedAtMs = 15L,
            sourceRevision = "source-2",
        )
        val promotion = WorkspaceSkillOverlayPromotion.evaluate(
            old, overlay, listOf(evidence))
        val request = WorkspaceSkillUpdate.request(
            current, changed, changedSnapshot, promotion)

        val updated = store.update(
            old.name, changed, changedFiles, promotion,
            request, request.approvalToken, 20L)

        assertEquals(changedSnapshot.packageSha256, updated.entry.packageSha256)
        assertEquals(changed.contentSha256, updated.entry.contentSha256)
        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED, updated.entry.state)
        assertNull(updated.entry.enabledAtMs)
        assertTrue(File(
            root, "packages/${current.snapshot.packageSha256}/SKILL.md").isFile)
        assertTrue(File(
            root, "packages/${changedSnapshot.packageSha256}/SKILL.md").isFile)
    }

    @Test fun updatingEnabledSkillAlwaysReturnsToDisabledState() {
        val root = temp.newFolder("skill-update-enabled")
        val store = WorkspaceSkillStore(root)
        val old = parsed()
        val installed = install(store, old, 10L)
        val report = WorkspaceSkillEnablement.test(installed, environment(), 11L)
        val enable = WorkspaceSkillEnablement.enableRequest(installed, report)
        val current = store.enable(
            old.name, environment(), enable, enable.approvalToken, 12L)
        assertEquals(WorkspaceSkillCatalog.State.ENABLED, current.entry.state)

        val changed = parsed(description = "Improved reviewed skill.")
        val changedFiles = files(changed)
        val snapshot = WorkspaceSkillCatalog.snapshot(changed, changedFiles)
        val ref = "verify:review-code:update-enabled"
        val overlay = WorkspaceSkillOverlay.Overlay(
            baseContentSha256 = old.contentSha256,
            descriptionOverride = changed.description,
            evidenceRefs = listOf(ref),
            createdAtMs = 13L,
        )
        val evidence = WorkspaceSkillImprovementEvidence.Record(
            ref = ref,
            skillName = old.name,
            baseContentSha256 = old.contentSha256,
            kind = WorkspaceSkillImprovementEvidence.Kind.USER_CONFIRMED,
            signal = WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
            capturedAtMs = 13L,
            sourceRevision = "source-3",
        )
        val promotion = WorkspaceSkillOverlayPromotion.evaluate(
            old, overlay, listOf(evidence))
        val request = WorkspaceSkillUpdate.request(
            current, changed, snapshot, promotion)

        val updated = store.update(
            old.name, changed, changedFiles, promotion,
            request, request.approvalToken, 14L)
        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED, updated.entry.state)
        assertNull(updated.entry.enableReadinessSha256)
        assertNull(updated.entry.enableEnvironmentSha256)
        assertNull(updated.entry.enableBindingSha256)
    }

    @Test fun rejectedUpdateDoesNotSwitchCatalogOrDeleteOldPackage() {
        val root = temp.newFolder("skill-update-reject")
        val store = WorkspaceSkillStore(root)
        val old = parsed()
        val current = install(store, old, 10L)

        val changed = parsed(description = "Changed.")
        val changedFiles = files(changed)
        val snapshot = WorkspaceSkillCatalog.snapshot(changed, changedFiles)
        val ref = "verify:review-code:update-reject"
        val overlay = WorkspaceSkillOverlay.Overlay(
            baseContentSha256 = old.contentSha256,
            descriptionOverride = changed.description,
            evidenceRefs = listOf(ref),
            createdAtMs = 15L,
        )
        val evidence = WorkspaceSkillImprovementEvidence.Record(
            ref = ref,
            skillName = old.name,
            baseContentSha256 = old.contentSha256,
            kind = WorkspaceSkillImprovementEvidence.Kind.DETERMINISTIC_VERIFICATION,
            signal = WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
            capturedAtMs = 15L,
            sourceRevision = "source-2",
        )
        val promotion = WorkspaceSkillOverlayPromotion.evaluate(
            old, overlay, listOf(evidence))
        val request = WorkspaceSkillUpdate.request(
            current, changed, snapshot, promotion)

        assertTrue(runCatching {
            store.update(
                old.name, changed, changedFiles, promotion,
                request, "wrong-token", 20L)
        }.isFailure)

        val reopened = store.load(old.name)
        assertEquals(current.entry, reopened.entry)
        assertTrue(File(
            root, "packages/${current.snapshot.packageSha256}/SKILL.md").isFile)
        assertFalse(File(root, "packages/${snapshot.packageSha256}").exists())
    }

}
