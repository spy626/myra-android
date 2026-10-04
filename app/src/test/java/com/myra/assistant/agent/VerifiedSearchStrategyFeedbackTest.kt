package com.myra.assistant.agent

import org.junit.Assert.*
import org.junit.Test

class VerifiedSearchStrategyFeedbackTest {
    private val two = setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH)
    private fun task(id: String, app: String? = "com.android.chrome") = GeneralRuntimeTask(
        id = id, turnId = 1L, createdAt = 1L,
        intent = StructuredAgentIntent(TurnIntent.ACTION_REQUEST, "read", "public research", true,
            relevantApp = app, requiredCapabilities = two, confidence = .9))
    private fun step(id: String, tool: ToolCapability) = GeneralPlanStep(
        taskId = id, category = ActionCategory.WEB_SEARCH, capability = tool,
        expectedOutcome = ExpectedOutcome(ExpectedOutcomeType.RESULT_SET_CHANGED, "results"))
    private fun verdict(status: GeneralVerificationStatus, fresh: Boolean = true,
        confidence: Double = .9) = GeneralVerificationResult(
        status, "expected", "observed", confidence,
        evidence = if (fresh) listOf("fresh_observation") else emptyList())

    @Test fun twoIndependentVerifiedSuccessesCanRankOnlyDeclaredExecutableEquivalent() {
        val feedback = VerifiedSearchStrategyFeedback()
        assertTrue(feedback.record(task("one"), step("one", ToolCapability.WEB_SEARCH),
            verdict(GeneralVerificationStatus.SUCCESS)))
        assertTrue(feedback.record(task("two"), step("two", ToolCapability.WEB_SEARCH),
            verdict(GeneralVerificationStatus.SUCCESS)))
        assertEquals(VerifiedSearchStrategyFeedback.Counts(2, 0),
            feedback.counts(ToolCapability.WEB_SEARCH, "com.android.chrome"))
        assertEquals(ToolCapability.WEB_SEARCH, feedback.recommend(ToolCapability.BROWSER_SEARCH,
            ToolCapability.WEB_SEARCH, two, two, "com.android.chrome"))
        assertEquals(ToolCapability.BROWSER_SEARCH, feedback.recommend(ToolCapability.BROWSER_SEARCH,
            ToolCapability.WEB_SEARCH, two, setOf(ToolCapability.BROWSER_SEARCH), "com.android.chrome"))
        assertEquals(ToolCapability.BROWSER_SEARCH, feedback.recommend(ToolCapability.BROWSER_SEARCH,
            ToolCapability.WEB_SEARCH, setOf(ToolCapability.BROWSER_SEARCH), two, "com.android.chrome"))
        assertEquals(ToolCapability.BROWSER_SEARCH, feedback.recommend(ToolCapability.BROWSER_SEARCH,
            ToolCapability.WEB_SEARCH, two, two, "org.mozilla.firefox"))
    }

    @Test fun unverifiedUnknownDuplicateOrUnrelatedActionCannotTrainPlanner() {
        val f = VerifiedSearchStrategyFeedback()
        assertFalse(f.record(task("a"), step("a", ToolCapability.WEB_SEARCH),
            verdict(GeneralVerificationStatus.UNKNOWN)))
        assertFalse(f.record(task("a"), step("a", ToolCapability.WEB_SEARCH),
            verdict(GeneralVerificationStatus.SUCCESS, fresh = false)))
        assertFalse(f.record(task("a"), step("a", ToolCapability.WEB_SEARCH),
            verdict(GeneralVerificationStatus.SUCCESS, confidence = .59)))
        assertFalse(f.record(task("a"), step("forged", ToolCapability.WEB_SEARCH),
            verdict(GeneralVerificationStatus.SUCCESS)))
        assertFalse(f.record(task("a"), step("a", ToolCapability.ACCESSIBILITY_CLICK),
            verdict(GeneralVerificationStatus.SUCCESS)))
        assertTrue(f.record(task("a"), step("a", ToolCapability.WEB_SEARCH),
            verdict(GeneralVerificationStatus.SUCCESS)))
        assertFalse(f.record(task("a"), step("a", ToolCapability.WEB_SEARCH),
            verdict(GeneralVerificationStatus.FAILURE)))
        assertEquals(1, f.counts(ToolCapability.WEB_SEARCH, "com.android.chrome").successes)
        assertEquals(ToolCapability.BROWSER_SEARCH, f.recommend(ToolCapability.BROWSER_SEARCH,
            ToolCapability.WEB_SEARCH, two, two, "com.android.chrome"))
    }

    @Test fun verifiedFailuresRemovePreferenceAndHistoryHasFixedBound() {
        val f = VerifiedSearchStrategyFeedback(limit = 4)
        for (i in 1..2) assertTrue(f.record(task("s$i"), step("s$i", ToolCapability.WEB_SEARCH),
            verdict(GeneralVerificationStatus.SUCCESS)))
        assertEquals(ToolCapability.WEB_SEARCH, f.recommend(ToolCapability.BROWSER_SEARCH,
            ToolCapability.WEB_SEARCH, two, two, "com.android.chrome"))
        for (i in 1..2) assertTrue(f.record(task("f$i"), step("f$i", ToolCapability.WEB_SEARCH),
            verdict(GeneralVerificationStatus.FAILURE)))
        assertEquals(ToolCapability.BROWSER_SEARCH, f.recommend(ToolCapability.BROWSER_SEARCH,
            ToolCapability.WEB_SEARCH, two, two, "com.android.chrome"))
        assertTrue(f.record(task("later"), step("later", ToolCapability.WEB_SEARCH),
            verdict(GeneralVerificationStatus.FAILURE)))
        assertEquals(VerifiedSearchStrategyFeedback.Counts(1, 3),
            f.counts(ToolCapability.WEB_SEARCH, "com.android.chrome"))
    }
}
