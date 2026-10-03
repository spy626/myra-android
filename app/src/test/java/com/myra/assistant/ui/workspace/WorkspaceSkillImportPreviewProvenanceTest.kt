package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSkillImportPreviewProvenanceTest {
    @Test fun pinnedGithubProvenanceIsRenderedWithoutChangingSecurityStatus() {
        val sha = "1234567890abcdef1234567890abcdef12345678"
        val md = """---
name: pinned-review
description: Pinned review skill.
---
## Verification
Require deterministic verification.
""".toByteArray()
        val preview = WorkspaceSkillImportPreview.inspect(
            skillMdBytes = md,
            provenance = WorkspaceSkillContract.Provenance(
                origin = WorkspaceSkillContract.Origin.GITHUB_PINNED,
                sourceUrl = "https://github.com/a/b/blob/" + sha + "/SKILL.md",
                pinnedRevision = sha,
            ),
        )
        assertEquals(
            WorkspaceSkillImportPreview.Status.READY_FOR_INSTALL_REVIEW,
            preview.status,
        )
        assertEquals(
            "GitHub · pinned revision",
            preview.rows.first { it.label == "Origin" }.value,
        )
        assertEquals(
            sha,
            preview.rows.first { it.label == "Pinned revision" }.value,
        )
        assertTrue(
            preview.rows.first { it.label == "Source URL" }.value
                .contains("/blob/" + sha + "/SKILL.md")
        )
    }
}
