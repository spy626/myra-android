package com.myra.assistant.ui.workspace

/** Small, UI-independent policy so editor space and file actions remain predictable. */
internal object WorkspaceEditorPanelPolicy {
    fun afterEditorFocus(filesOpen: Boolean): Boolean = false
    fun afterFileSelected(filesOpen: Boolean): Boolean = false
    fun afterFilesButton(filesOpen: Boolean): Boolean = !filesOpen
}
