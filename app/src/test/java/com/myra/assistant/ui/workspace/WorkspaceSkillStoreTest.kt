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
}
