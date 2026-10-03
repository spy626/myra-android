package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSkillImportPreviewTest {
    private fun md(body: String = "## Verification\nRequire deterministic evidence."): ByteArray =
        """---
name: local-review
description: Review a project locally.
license: MIT
---
""" .plus(body).toByteArray()

    private fun value(preview: WorkspaceSkillImportPreview.Preview, label: String): String =
        preview.rows.first { it.label == label }.value

    @Test fun minimalLocalSkillProducesBoundedPreviewWithoutInstallingAnything() {
        val preview = WorkspaceSkillImportPreview.inspect(md())

        assertEquals(
            WorkspaceSkillImportPreview.Status.READY_FOR_INSTALL_REVIEW,
            preview.status,
        )
        assertEquals("local-review", preview.name)
        assertEquals("User supplied · local preview only", value(preview, "Origin"))
        assertEquals("Declared", value(preview, "Verification gate"))
        assertEquals("SKILL.md", value(preview, "Selected files"))
        assertEquals(64, value(preview, "Content SHA-256").length)
        assertEquals(64, value(preview, "Package SHA-256").length)
        assertEquals(64, value(preview, "Permission SHA-256").length)
        assertTrue(preview.warnings.any { it.contains("installation/enabling") })
    }

    @Test fun manifestPermissionsAreShownExactlyAndRemainPreviewOnly() {
        val json = """{
          "allowedTools":["read_file"],
          "requiredCapabilities":["project:read"],
          "networkDomains":["api.github.com"],
          "sourceSharing":"BOUNDED",
          "memoryAccess":"READ",
          "dependencySkills":["base-review"],
          "modelInvocable":true
        }""".toByteArray()

        val preview = WorkspaceSkillImportPreview.inspect(md(), json)

        assertEquals("read_file", value(preview, "Allowed tools"))
        assertEquals("project:read", value(preview, "Required capabilities"))
        assertEquals("api.github.com", value(preview, "Network domains"))
        assertEquals("BOUNDED", value(preview, "Source sharing"))
        assertEquals("READ", value(preview, "Memory access"))
        assertEquals("base-review", value(preview, "Dependencies"))
        assertEquals("true", value(preview, "Model invocable"))
        assertTrue(value(preview, "Selected files").contains("skill.json"))
    }

    @Test fun possibleSecretBlocksPreviewFromAdvancingToInstallReview() {
        val secretMd = md(
            "## Verification\nRequire deterministic evidence.\napi_key = sk-abcdefghijklmnop"
        )
        val preview = WorkspaceSkillImportPreview.inspect(secretMd)

        assertEquals(WorkspaceSkillImportPreview.Status.BLOCKED_SECRET, preview.status)
        assertTrue(preview.warnings.any { it.contains("Possible credential/secret") })
    }

    @Test fun invalidUtf8AndOversizeFilesFailClosed() {
        assertTrue(runCatching {
            WorkspaceSkillImportPreview.inspect(byteArrayOf(0xC3.toByte(), 0x28))
        }.isFailure)

        assertTrue(runCatching {
            WorkspaceSkillImportPreview.inspect(
                ByteArray(WorkspaceSkillImportPreview.MAX_FILE_BYTES + 1) { 'a'.code.toByte() }
            )
        }.isFailure)
    }

    @Test fun executableManifestFieldsRemainRejectedByExistingContract() {
        val json = """{"command":"rm -rf /"}""".toByteArray()
        assertTrue(runCatching {
            WorkspaceSkillImportPreview.inspect(md(), json)
        }.isFailure)
    }
}
