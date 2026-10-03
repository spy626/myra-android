package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceSkillCatalogTest {
    private fun skill(
        name: String = "review-code",
        description: String = "Review code safely.",
        manifest: String? = null,
    ): WorkspaceSkillContract.ParsedSkill {
        val md = """---
name: $name
description: $description
---
## Verification
Require deterministic evidence.
"""
        val paths = if (manifest == null) listOf("SKILL.md")
            else listOf("SKILL.md", "skill.json")
        return WorkspaceSkillContract.parse(md, manifest, packagePaths = paths)
    }

    private fun files(skill: WorkspaceSkillContract.ParsedSkill): Map<String, ByteArray> =
        buildMap {
            put("SKILL.md", skill.originalSkillMd.toByteArray())
            skill.originalSkillJson?.let { put("skill.json", it.toByteArray()) }
        }

    @Test fun approvalBindsExactPackageAndPermissionsAndStartsDisabled() {
        val parsed = skill(
            manifest = """{"allowedTools":["read_file"],"memoryAccess":"READ"}""")
        val snapshot = WorkspaceSkillCatalog.snapshot(parsed, files(parsed))
        val request = WorkspaceSkillCatalog.approvalRequest(parsed, snapshot)

        val catalog = WorkspaceSkillCatalog.admit(
            WorkspaceSkillCatalog.Catalog(),
            parsed,
            snapshot,
            request,
            request.approvalToken,
            installedAtMs = 123L,
        )
        val entry = catalog.entries.single()
        assertEquals("review-code", entry.name)
        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED, entry.state)
        assertEquals(snapshot.packageSha256, entry.packageSha256)
        assertEquals(request.permissionSha256, entry.permissionSha256)
    }

    @Test fun changedBytesAfterInspectionCannotReuseApproval() {
        val parsed = skill()
        val snapshot = WorkspaceSkillCatalog.snapshot(parsed, files(parsed))
        val request = WorkspaceSkillCatalog.approvalRequest(parsed, snapshot)
        val changed = files(parsed).toMutableMap().apply {
            this["SKILL.md"] = (parsed.originalSkillMd + "\nchanged").toByteArray()
        }

        assertTrue(runCatching {
            WorkspaceSkillCatalog.snapshot(parsed, changed)
        }.isFailure)

        val other = skill(description = "Different reviewed description.")
        val otherSnapshot = WorkspaceSkillCatalog.snapshot(other, files(other))
        assertTrue(runCatching {
            WorkspaceSkillCatalog.admit(
                WorkspaceSkillCatalog.Catalog(),
                other,
                otherSnapshot,
                request,
                request.approvalToken,
                1L,
            )
        }.isFailure)
    }

    @Test fun unapprovedOrWrongApprovalTokenCannotInstall() {
        val parsed = skill()
        val snapshot = WorkspaceSkillCatalog.snapshot(parsed, files(parsed))
        val request = WorkspaceSkillCatalog.approvalRequest(parsed, snapshot)
        assertTrue(runCatching {
            WorkspaceSkillCatalog.admit(
                WorkspaceSkillCatalog.Catalog(),
                parsed,
                snapshot,
                request,
                "not-approved",
                1L,
            )
        }.isFailure)
    }

    @Test fun sameNameDifferentPackageRequiresSeparateUpdateFlow() {
        val first = skill()
        val firstSnapshot = WorkspaceSkillCatalog.snapshot(first, files(first))
        val firstApproval = WorkspaceSkillCatalog.approvalRequest(first, firstSnapshot)
        val installed = WorkspaceSkillCatalog.admit(
            WorkspaceSkillCatalog.Catalog(),
            first,
            firstSnapshot,
            firstApproval,
            firstApproval.approvalToken,
            1L,
        )

        val changed = skill(description = "Review code with fresh evidence.")
        val changedSnapshot = WorkspaceSkillCatalog.snapshot(changed, files(changed))
        val changedApproval = WorkspaceSkillCatalog.approvalRequest(changed, changedSnapshot)
        assertTrue(runCatching {
            WorkspaceSkillCatalog.admit(
                installed,
                changed,
                changedSnapshot,
                changedApproval,
                changedApproval.approvalToken,
                2L,
            )
        }.isFailure)
    }

    @Test fun reinstallingExactSamePackageIsIdempotent() {
        val parsed = skill()
        val snapshot = WorkspaceSkillCatalog.snapshot(parsed, files(parsed))
        val approval = WorkspaceSkillCatalog.approvalRequest(parsed, snapshot)
        val once = WorkspaceSkillCatalog.admit(
            WorkspaceSkillCatalog.Catalog(),
            parsed,
            snapshot,
            approval,
            approval.approvalToken,
            10L,
        )
        val twice = WorkspaceSkillCatalog.admit(
            once,
            parsed,
            snapshot,
            approval,
            approval.approvalToken,
            99L,
        )
        assertEquals(once, twice)
        assertEquals(10L, twice.entries.single().installedAtMs)
    }

    @Test fun manifestBytesAndSupportingPathsArePartOfPackageHash() {
        val parsed = skill(
            manifest = """{"allowedTools":["read_file"]}""")
        val baseFiles = files(parsed).toMutableMap()
        baseFiles["references/checklist.md"] = "A".toByteArray()
        val first = WorkspaceSkillCatalog.snapshot(parsed, baseFiles)
        baseFiles["references/checklist.md"] = "B".toByteArray()
        val second = WorkspaceSkillCatalog.snapshot(parsed, baseFiles)
        assertNotEquals(first.packageSha256, second.packageSha256)
        assertNotEquals(
            first.files.first { it.path == "references/checklist.md" }.sha256,
            second.files.first { it.path == "references/checklist.md" }.sha256)
    }
}
