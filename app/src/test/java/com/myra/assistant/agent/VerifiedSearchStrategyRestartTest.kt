package com.myra.assistant.agent

import org.junit.Assert.*
import org.junit.Test

class VerifiedSearchStrategyRestartTest {
    private fun task(id: String) = GeneralRuntimeTask(
        id = id, turnId = 5L, createdAt = 1L,
        intent = StructuredAgentIntent(
            TurnIntent.ACTION_REQUEST, "read", "research", true, relevantApp = "com.android.chrome",
            requiredCapabilities = setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH),
            confidence = .9))
    private fun step(id: String) = GeneralPlanStep(taskId = id,
        category = ActionCategory.WEB_SEARCH, capability = ToolCapability.WEB_SEARCH,
        expectedOutcome = ExpectedOutcome(ExpectedOutcomeType.RESULT_SET_CHANGED, "results"))
    private val proven = GeneralVerificationResult(GeneralVerificationStatus.SUCCESS,
        "results", "verified", .9, evidence = listOf("fresh_observation"))

    @Test fun restoredVerifiedOutcomesChangeNextSafeEquivalentWithoutRawTaskIdentity() {
        val first = VerifiedSearchStrategyFeedback()
        assertTrue(first.record(task("internal-task-1"), step("internal-task-1"), proven))
        assertTrue(first.record(task("internal-task-2"), step("internal-task-2"), proven))
        val durable = first.snapshot()
        assertEquals(2, durable.size)
        assertTrue(durable.all { it.taskKey.matches(Regex("[0-9a-f]{64}")) })
        assertFalse(durable.toString().contains("internal-task"))

        val restarted = VerifiedSearchStrategyFeedback()
        restarted.restore(durable + durable)
        assertEquals(2, restarted.snapshot().size)
        assertEquals(ToolCapability.WEB_SEARCH, restarted.recommend(
            ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH,
            setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH),
            setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH), "com.android.chrome"))
    }

    @Test fun lateRestoreCannotOverwriteNewerLocalEvidenceAndInvalidRowsAreIgnored() {
        val current = VerifiedSearchStrategyFeedback()
        assertTrue(current.record(task("internal-task-1"), step("internal-task-1"), proven))
        val fresh = current.snapshot().single()
        current.restore(listOf(
            fresh.copy(status = GeneralVerificationStatus.FAILURE),
            fresh.copy(taskKey = "forged"),
            fresh.copy(status = GeneralVerificationStatus.UNKNOWN)))
        assertEquals(listOf(fresh), current.snapshot())
    }

    @Test fun runtimeExposesExactlyOncePersistenceOutboxAndCanImportRestoredHistory() {
        val runtime = GeneralAgentRuntime(now = { 99L })
        fun search(turn: Long) {
            val intent = StructuredAgentIntent(
                TurnIntent.ACTION_REQUEST, "search", "public research", true,
                relevantApp = "com.android.chrome", textHint = "ai",
                requiredCapabilities = setOf(ToolCapability.WEB_SEARCH), confidence = .9)
            val t = runtime.start(turn, intent)!!
            val beforeScene = ScreenScene("com.android.chrome", "com.android.chrome",
                windowId = 1, generation = turn * 10, screenType = "SEARCH",
                semanticElements = emptyList(), screenshotReference = null,
                observedAt = turn * 10, confidence = .9)
            val before = PerceptionSnapshot(beforeScene, t.id, beforeScene.observedAt)
            val planned = (runtime.next(before) as PlannerResult.Next).step
            runtime.recordAction(planned, GeneralActionResult(true), before)
            val resultElement = SemanticElement("result", SemanticRole.BUTTON, "ai results",
                0, 0, 100, 100, true)
            val after = PerceptionSnapshot(beforeScene.copy(
                generation = turn * 10 + 1, observedAt = turn * 10 + 1,
                semanticElements = listOf(resultElement)), t.id, turn * 10 + 1)
            assertEquals(GeneralVerificationStatus.SUCCESS, runtime.verify(after).first.status)
        }
        search(30L)
        val outbox = runtime.takeVerifiedSearchOutcomes()
        assertEquals(1, outbox.size)
        assertTrue(runtime.takeVerifiedSearchOutcomes().isEmpty())
        val restarted = GeneralAgentRuntime(now = { 100L })
        restarted.restoreVerifiedSearchOutcomes(outbox + outbox)
        search(31L) // In original runtime, different execution identity.
        assertEquals(1, runtime.takeVerifiedSearchOutcomes().size)
    }
}
