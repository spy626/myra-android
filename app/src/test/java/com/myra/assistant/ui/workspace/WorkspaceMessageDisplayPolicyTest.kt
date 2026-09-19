package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceMessageDisplayPolicyTest {
    @Test fun shortMessagesStayExpanded() {
        assertFalse(WorkspaceMessageDisplayPolicy.shouldCollapse("Hi bro"))
        assertFalse(WorkspaceMessageDisplayPolicy.shouldCollapse("Line one\nLine two"))
    }

    @Test fun longOrMultilinePromptsGetFiveLineToggleWithoutChangingText() {
        val pasted = "A".repeat(WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS)
        assertTrue(WorkspaceMessageDisplayPolicy.shouldCollapse(pasted))
        assertEquals(WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS, pasted.length)
        assertTrue(WorkspaceMessageDisplayPolicy.shouldCollapse("a\nb\nc\nd\ne\nf"))
        assertEquals(5, WorkspaceMessageDisplayPolicy.COLLAPSED_LINES)
    }
}
