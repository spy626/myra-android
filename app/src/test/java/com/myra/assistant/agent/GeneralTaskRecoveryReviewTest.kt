package com.myra.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralTaskRecoveryReviewTest {
    private val pending = GeneralTaskCheckpointStore.Checkpoint(
        "task-1", 12L, 2, AgentRuntimeStatus.WAITING_FOR_RESULT,
        emptyList(), true, false, 1000L
    )
    @Test fun reviewNeedsUserToRestateTheWholeTask() {
        assertTrue(GeneralTaskRecoveryReview.prepare(
            "continue", pending) is GeneralTaskRecoveryReview.Draft.Rejected)
        assertTrue(GeneralTaskRecoveryReview.prepare(
            "resume task", pending) is GeneralTaskRecoveryReview.Draft.Rejected)
        val draft = GeneralTaskRecoveryReview.prepare(
            "Search official Android WorkManager docs", pending)
        assertEquals("Search official Android WorkManager docs",
            (draft as GeneralTaskRecoveryReview.Draft.Ready).freshInstruction)
        assertFalse(GeneralTaskRecoveryReview.mayExecuteFromReview())
    }
    @Test fun noPendingTaskMeansNoRecoveryAuthority() {
        assertTrue(GeneralTaskRecoveryReview.prepare(
            "Open browser and search for current news", null)
            is GeneralTaskRecoveryReview.Draft.Rejected)
        assertTrue(GeneralTaskRecoveryReview.prepare(
            "Open browser and search for current news",
            pending.copy(status = AgentRuntimeStatus.COMPLETED, needsFreshObservation = false))
            is GeneralTaskRecoveryReview.Draft.Rejected)
    }
}
