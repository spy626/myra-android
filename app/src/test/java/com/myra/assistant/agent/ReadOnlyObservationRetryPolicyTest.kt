package com.myra.assistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyObservationRetryPolicyTest {
    private fun context(packageName: String = "com.android.chrome",
                        labels: List<String> = emptyList(), time: Long = 1L) =
        PerceptionSnapshot(
            ScreenScene(packageName, packageName, windowId = 1,
                generation = time, screenType = "BROWSER",
                semanticElements = labels.mapIndexed { i, label ->
                    SemanticElement(i.toString(), SemanticRole.TEXT, label,
                        0, i * 40, 300, i * 40 + 30, false)
                },
                screenshotReference = null, observedAt = time, confidence = .9),
            "task-one", time)
    private fun step(capability: ToolCapability = ToolCapability.BROWSER_SEARCH) =
        GeneralPlanStep(taskId = "task-one", category = ActionCategory.BROWSER,
            capability = capability,
            expectedOutcome = ExpectedOutcome(ExpectedOutcomeType.RESULT_SET_CHANGED,
                "Public search results", "com.android.chrome", "coroutines"))

    @Test fun silentlyReobservesDelayedPublicSearchAtMostTwice() {
        val before = context(time = 1)
        val after = context(time = 2)
        assertTrue(ReadOnlyObservationRetryPolicy.shouldResample(
            step(), true, before, after, 0))
        assertTrue(ReadOnlyObservationRetryPolicy.shouldResample(
            step(), true, before, after, 1))
        assertFalse(ReadOnlyObservationRetryPolicy.shouldResample(
            step(), true, before, after, 2))
        assertFalse(ReadOnlyObservationRetryPolicy.shouldResample(
            step(), true, before, context(labels = listOf("coroutines docs"), time = 3), 0))
    }

    @Test fun cannotAutoretryTapsScrollSendsWrongAppOrProtectedDialogs() {
        val before = context()
        val after = context(time = 2)
        assertFalse(ReadOnlyObservationRetryPolicy.shouldResample(
            step(ToolCapability.ACCESSIBILITY_CLICK), true, before, after, 0))
        assertFalse(ReadOnlyObservationRetryPolicy.shouldResample(
            step(ToolCapability.ACCESSIBILITY_SCROLL), true, before, after, 0))
        assertFalse(ReadOnlyObservationRetryPolicy.shouldResample(
            step(), false, before, after, 0))
        assertFalse(ReadOnlyObservationRetryPolicy.shouldResample(
            step(), true, before, context("com.example.other", time = 2), 0))
        assertFalse(ReadOnlyObservationRetryPolicy.shouldResample(
            step(), true, before, context(time = 2).copy(
                scene = after.scene.copy(modal = ModalKind.PERMISSION)), 0))
    }
}
