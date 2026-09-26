package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceEditorPanelPolicyTest {
    @Test fun filesButtonTogglesPanel() {
        assertTrue(WorkspaceEditorPanelPolicy.afterFilesButton(false))
        assertFalse(WorkspaceEditorPanelPolicy.afterFilesButton(true))
    }

    @Test fun editorFocusAndFileSelectionFreeEditorSpace() {
        assertFalse(WorkspaceEditorPanelPolicy.afterEditorFocus(true))
        assertFalse(WorkspaceEditorPanelPolicy.afterFileSelected(true))
    }
}
