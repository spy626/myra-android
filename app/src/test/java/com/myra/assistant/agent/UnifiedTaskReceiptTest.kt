package com.myra.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedTaskReceiptTest {
    private val intent = StructuredAgentIntent(
        TurnIntent.ACTION_REQUEST, "Search the web", "search",
        requiresAction = true, confidence = .98,
    )

    @Test fun aCompletedLabelIsNotAnObservedSuccessWithoutEvidence() {
        val task = GeneralRuntimeTask(
            id = "t1", turnId = 11, intent = intent,
            status = AgentRuntimeStatus.COMPLETED, createdAt = 1L)
        assertFalse(UnifiedTaskReceiptPolicy.mayReportSuccess(
            UnifiedTaskReceiptPolicy.from(task)))
    }

    @Test fun verifiedSubstepMakesCompletionAuditable() {
        val task = GeneralRuntimeTask(
            id = "t2", turnId = 22, intent = intent,
            status = AgentRuntimeStatus.COMPLETED, createdAt = 1L,
            verifiedGoalSubsteps = listOf(VerifiedGoalSubstep(
                "s1", ToolCapability.WEB_SEARCH, "com.android.chrome",
                3L, listOf("search results observed"))))
        val receipt = UnifiedTaskReceiptPolicy.from(task)
        assertTrue(receipt.verifiedComplete)
        assertEquals(listOf("s1"), receipt.lastVerifiedStepIds)
        assertFalse(receipt.needsFreshObservation)
    }

    @Test fun approvalWaitNeverMeansAutomaticPermissionOrResume() {
        val task = GeneralRuntimeTask(
            id = "t3", turnId = 33, intent = intent,
            status = AgentRuntimeStatus.WAITING_FOR_USER_AUTH, createdAt = 1L)
        val receipt = UnifiedTaskReceiptPolicy.from(task)
        assertTrue(receipt.waitingForApproval)
        assertTrue(receipt.needsFreshObservation)
        assertFalse(receipt.verifiedComplete)
    }
}
