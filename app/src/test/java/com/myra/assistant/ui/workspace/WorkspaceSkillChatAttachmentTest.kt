package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSkillChatAttachmentTest {
    @Test fun exactSkillMdWithinBoundIsAccepted() {
        val verified = WorkspaceSkillChatAttachment.validate("SKILL.md", 128)
        assertEquals("SKILL.md", verified.name)
        assertEquals(128L, verified.size)
    }

    @Test fun wrongNameAndUnsafeSizeFailClosed() {
        assertTrue(runCatching {
            WorkspaceSkillChatAttachment.validate("skill.md", 128)
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceSkillChatAttachment.validate("SKILL.md", 0)
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceSkillChatAttachment.validate(
                "SKILL.md",
                WorkspaceSkillImportPreview.MAX_FILE_BYTES.toLong() + 1,
            )
        }.isFailure)
    }

    @Test fun createSkillStarterIsShortAndExplicit() {
        assertEquals("Create a skill that ", WorkspaceSkillChatAttachment.CREATE_SKILL_PROMPT)
    }
}
