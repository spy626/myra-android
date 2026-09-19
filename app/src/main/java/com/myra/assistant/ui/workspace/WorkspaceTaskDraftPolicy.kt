package com.myra.assistant.ui.workspace

/** Compare both visible fields against saved project data; neither edit is an approval or a saved task. */
object WorkspaceTaskDraftPolicy {
    fun isDirty(visibleDraft: String, savedGoal: String?, visibleCriteria: String = "", savedCriteria: String? = ""): Boolean =
        visibleDraft != savedGoal.orEmpty() || visibleCriteria != savedCriteria.orEmpty()
}
