package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceCodingActionPolicyTest {
    @Test fun acceptedWebsiteAfterKeepOrUndoHasNoAction() {
        assertEquals(WorkspaceCodingActionPolicy.Action.NONE,
            WorkspaceCodingActionPolicy.next(false, false, false))
    }

    @Test fun currentWebsiteBackupAloneShowsReview() {
        assertEquals(WorkspaceCodingActionPolicy.Action.REVIEW_WEBSITE,
            WorkspaceCodingActionPolicy.next(true, false, false))
    }

    @Test fun fileBackupAndSavedProposalUseTheirOwnReviewActions() {
        assertEquals(WorkspaceCodingActionPolicy.Action.REVIEW_EDIT,
            WorkspaceCodingActionPolicy.next(false, true, false))
        assertEquals(WorkspaceCodingActionPolicy.Action.REVIEW_SAVED_PROPOSAL,
            WorkspaceCodingActionPolicy.next(false, false, true))
    }

    @Test fun liveRollbackTakesPriorityOverStaleProposal() {
        assertEquals(WorkspaceCodingActionPolicy.Action.REVIEW_WEBSITE,
            WorkspaceCodingActionPolicy.next(true, true, true))
        assertEquals(WorkspaceCodingActionPolicy.Action.REVIEW_EDIT,
            WorkspaceCodingActionPolicy.next(false, true, true))
    }
}
