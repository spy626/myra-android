package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSkillReadinessSurfaceTest {
    private fun installed(
        name: String = "plain-skill",
        manifestJson: String? = null,
    ): WorkspaceSkillStore.Installed {
        val skill = WorkspaceSkillContract.parse(
            """---
name: """ + name + """
description: Test skill.
---
## Verification
Require deterministic verification.
""",
            manifestJson,
            packagePaths = if (manifestJson == null) listOf("SKILL.md")
            else listOf("SKILL.md", "skill.json"),
        )
        val files = linkedMapOf<String, ByteArray>(
            "SKILL.md" to skill.originalSkillMd.toByteArray(),
        )
        if (manifestJson != null) {
            files["skill.json"] = requireNotNull(skill.originalSkillJson).toByteArray()
        }
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, files)
        val approval = WorkspaceSkillCatalog.approvalRequest(skill, snapshot)
        val entry = WorkspaceSkillCatalog.Entry(
            name = skill.name,
            description = skill.description,
            contentSha256 = skill.contentSha256,
            packageSha256 = snapshot.packageSha256,
            permissionSha256 = approval.permissionSha256,
            provenance = skill.provenance,
            installedAtMs = 1L,
        )
        return WorkspaceSkillStore.Installed(entry, skill, snapshot)
    }

    @Test fun currentEnvironmentSeparatesInstalledAndEnabledDependencyNames() {
        val a = installed("alpha")
        val b = installed("beta")
        val env = WorkspaceSkillReadinessSurface.currentEnvironment(listOf(a, b))

        assertEquals(setOf("alpha", "beta"), env.installedSkills)
        assertTrue(env.enabledSkills.isEmpty())
        assertTrue(env.availableTools.isEmpty())
        assertTrue(env.availableCapabilities.isEmpty())
        assertFalse(env.boundedSourceGateAvailable)
        assertFalse(env.networkGateAvailable)
        assertFalse(env.memoryReadGateAvailable)
        assertFalse(env.memoryWriteGateAvailable)
        assertFalse(env.modelInvocationGateAvailable)
    }

    @Test fun plainInstructionSkillCanPassButPermissionedSkillBlocksHonestly() {
        val plain = installed()
        val env = WorkspaceSkillReadinessSurface.currentEnvironment(listOf(plain))
        val pass = WorkspaceSkillEnablement.test(plain, env, 10L)
        assertEquals(WorkspaceSkillEnablement.Status.PASS, pass.status)
        assertTrue(WorkspaceSkillReadinessSurface.view(pass).rows.all { it.passed })

        val networked = installed(
            "network-skill",
            """{"networkDomains":["example.com"]}""",
        )
        val blockedEnv = WorkspaceSkillReadinessSurface.currentEnvironment(listOf(networked))
        val blocked = WorkspaceSkillEnablement.test(networked, blockedEnv, 11L)
        assertEquals(WorkspaceSkillEnablement.Status.BLOCKED, blocked.status)
        assertTrue(
            WorkspaceSkillReadinessSurface.view(blocked).rows
                .any { it.id == "network-gate" && !it.passed }
        )
    }
}
