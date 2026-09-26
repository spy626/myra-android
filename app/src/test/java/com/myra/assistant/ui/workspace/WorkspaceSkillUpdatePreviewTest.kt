package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillUpdatePreviewTest {
    @get:Rule val temp = TemporaryFolder()

    private fun skill(
        description: String,
        manifest: String,
        provenance: WorkspaceSkillContract.Provenance =
            WorkspaceSkillContract.Provenance(WorkspaceSkillContract.Origin.USER_SUPPLIED),
    ): WorkspaceSkillContract.ParsedSkill =
        WorkspaceSkillContract.parse(
            """---
name: review-code
description: """ + description + """
---
## Verification
Require deterministic evidence.
""",
            manifest,
            provenance = provenance,
            packagePaths = listOf("SKILL.md", "skill.json"),
        )

    private fun files(skill: WorkspaceSkillContract.ParsedSkill) = linkedMapOf(
        "SKILL.md" to skill.originalSkillMd.toByteArray(),
        "skill.json" to requireNotNull(skill.originalSkillJson).toByteArray(),
    )

    private fun installed(
        store: WorkspaceSkillStore,
        skill: WorkspaceSkillContract.ParsedSkill,
    ): WorkspaceSkillStore.Installed {
        val packageFiles = files(skill)
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, packageFiles)
        val approval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        return store.install(
            skill, packageFiles, approval, approval.approvalToken, 1L)
    }

    private fun candidate(skill: WorkspaceSkillContract.ParsedSkill):
        WorkspaceSkillUpdatePreview.Candidate {
        val packageFiles = files(skill)
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, packageFiles)
        return WorkspaceSkillUpdatePreview.Candidate(
            skill,
            snapshot,
            WorkspaceSkillCatalog.approvalRequest(skill, snapshot).permissionSha256,
        )
    }

    @Test fun nonWideningPreviewShowsAddedRemovedUnchangedAndActivationImpact() {
        val store = WorkspaceSkillStore(temp.newFolder("update-preview"))
        val old = skill(
            "Old.",
            """{"allowedTools":["read_file","search"],"requiredCapabilities":["project:read"],"networkDomains":["api.github.com"],"dependencySkills":["base"],"sourceSharing":"BOUNDED","memoryAccess":"READ","userInvocable":true,"modelInvocable":false,"maxNestingDepth":2}""",
        )
        val current = installed(store, old)
        val next = skill(
            "New.",
            """{"allowedTools":["read_file"],"requiredCapabilities":["project:read"],"networkDomains":[],"dependencySkills":["base"],"sourceSharing":"NONE","memoryAccess":"NONE","userInvocable":false,"modelInvocable":false,"maxNestingDepth":1}""",
        )

        val preview = WorkspaceSkillUpdatePreview.compare(current, candidate(next))

        assertEquals(
            WorkspaceSkillUpdatePreview.Status.NON_WIDENING_PREVIEW,
            preview.status,
        )
        val tools = preview.setDeltas.first { it.label == "Allowed tools" }
        assertEquals(listOf("search"), tools.removed)
        assertEquals(listOf("read_file"), tools.unchanged)
        assertTrue(tools.added.isEmpty())
        assertEquals(
            "REDUCED",
            preview.scalarDeltas.first { it.label == "Memory access" }.classification,
        )
        assertTrue(preview.activationImpact.contains("INSTALLED_DISABLED"))
    }

    @Test fun permissionWideningIsVisibleAndBlockedByExistingPolicy() {
        val store = WorkspaceSkillStore(temp.newFolder("update-widen"))
        val old = skill(
            "Old.",
            """{"allowedTools":["read_file"],"sourceSharing":"NONE","memoryAccess":"NONE","modelInvocable":false}""",
        )
        val current = installed(store, old)
        val next = skill(
            "New.",
            """{"allowedTools":["read_file","write_file"],"sourceSharing":"BOUNDED","memoryAccess":"READ","modelInvocable":true}""",
        )

        val preview = WorkspaceSkillUpdatePreview.compare(current, candidate(next))

        assertEquals(
            WorkspaceSkillUpdatePreview.Status.BLOCKED_PERMISSION_WIDENING,
            preview.status,
        )
        assertEquals(
            listOf("write_file"),
            preview.setDeltas.first { it.label == "Allowed tools" }.added,
        )
        assertEquals(
            "WIDENED",
            preview.scalarDeltas.first { it.label == "Source sharing" }.classification,
        )
        assertEquals(
            "WIDENED",
            preview.scalarDeltas.first { it.label == "Model invocable" }.classification,
        )
    }

    @Test fun identicalPackageAndNameMismatchAreSeparated() {
        val store = WorkspaceSkillStore(temp.newFolder("update-edge"))
        val old = skill("Old.", """{"allowedTools":[]}""")
        val current = installed(store, old)

        assertEquals(
            WorkspaceSkillUpdatePreview.Status.IDENTICAL_PACKAGE,
            WorkspaceSkillUpdatePreview.compare(current, candidate(old)).status,
        )

        val other = WorkspaceSkillContract.parse(
            """---
name: another-skill
description: Other.
---
## Verification
Check.
""",
        )
        val otherFiles = mapOf("SKILL.md" to other.originalSkillMd.toByteArray())
        val otherSnapshot = WorkspaceSkillCatalog.snapshot(other, otherFiles)
        val otherCandidate = WorkspaceSkillUpdatePreview.Candidate(
            other,
            otherSnapshot,
            WorkspaceSkillCatalog.approvalRequest(other, otherSnapshot).permissionSha256,
        )
        assertEquals(
            WorkspaceSkillUpdatePreview.Status.BLOCKED_NAME_MISMATCH,
            WorkspaceSkillUpdatePreview.compare(current, otherCandidate).status,
        )
    }

    @Test fun candidateFromBytesPreservesPinnedGithubProvenanceAndSecretGate() {
        val sha = "1234567890abcdef1234567890abcdef12345678"
        val source = "https://github.com/a/b/blob/" + sha + "/SKILL.md"
        val md = """---
name: review-code
description: GitHub candidate.
---
## Verification
Check pinned evidence.
""".toByteArray()
        val provenance = WorkspaceSkillContract.Provenance(
            WorkspaceSkillContract.Origin.GITHUB_PINNED,
            source,
            sha,
        )
        val candidate = WorkspaceSkillUpdatePreview.candidateFromBytes(
            skillMdBytes = md,
            provenance = provenance,
        )
        assertEquals(WorkspaceSkillContract.Origin.GITHUB_PINNED, candidate.skill.provenance.origin)
        assertEquals(sha, candidate.skill.provenance.pinnedRevision)

        val secret = """---
name: review-code
description: api_key = sk-abcdefghijklmnop
---
## Verification
Check.
""".toByteArray()
        assertTrue(runCatching {
            WorkspaceSkillUpdatePreview.candidateFromBytes(secret)
        }.isFailure)
    }
}
