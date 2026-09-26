package com.myra.assistant.ui.workspace

/** UI-only collapsing. Full message stays in the TextView, store, Copy and request. */
internal object WorkspaceMessageDisplayPolicy {
    const val COLLAPSED_LINES = 5
    fun shouldCollapse(text: String): Boolean =
        text.length > 240 || text.count { it == '\n' } >= COLLAPSED_LINES
}
