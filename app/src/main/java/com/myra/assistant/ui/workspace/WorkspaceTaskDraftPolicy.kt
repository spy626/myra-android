package com.myra.assistant.ui.workspace

/** Compare the visible draft against the saved project task; never treat an edit as a saved task. */
object WorkspaceTaskDraftPolicy {
    fun isDirty(visibleDraft: String, savedGoal: String?): Boolean = visibleDraft != savedGoal.orEmpty()
}
