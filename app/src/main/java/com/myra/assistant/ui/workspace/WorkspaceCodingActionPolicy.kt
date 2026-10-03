package com.myra.assistant.ui.workspace

/** Chat action visibility is derived from the current on-disk edit/rollback records.
 * A saved user message is not proof that an edit ran, and Keep clears the review action.
 */
internal object WorkspaceCodingActionPolicy {
    enum class Action { REVIEW_WEBSITE, REVIEW_EDIT, REVIEW_SAVED_PROPOSAL, NONE }

    fun next(websiteBackup: Boolean, scopedBackup: Boolean, savedProposal: Boolean): Action = when {
        websiteBackup -> Action.REVIEW_WEBSITE
        scopedBackup -> Action.REVIEW_EDIT
        savedProposal -> Action.REVIEW_SAVED_PROPOSAL
        else -> Action.NONE
    }
}
