package com.myra.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentArchitectureTest {
    @Test fun assistant_overlay_preserves_external_task_package() {
        val context = CurrentActivityContext("com.myra.assistant", "LYRA", "OVERLAY", 4, 9, emptyList(), confidence = .9, timestamp = 1)
        val scene = ScreenSceneFactory.from(context, "com.google.android.youtube")
        assertEquals("com.google.android.youtube", scene.externalForegroundPackage)
        assertEquals("com.myra.assistant", scene.assistantUiPackage)
    }

    @Test fun new_modal_forces_observation_instead_of_stale_retry() {
        val task = AgentTask(originalUserRequest = "tap", interpretedGoal = AgentGoalType.TAP, expectedApp = "pkg")
        val scene = ScreenScene("com.android.systemui", "pkg", windowId = 2, generation = 3,
            screenType = "DIALOG", semanticElements = emptyList(), screenshotReference = null,
            modal = ModalKind.SYSTEM_DIALOG, observedAt = 1, confidence = .8)
        val result = AgentRecoveryPlanner.afterVerification(task, AgentActionRecord("tap", "x", true, false, 1), scene)
        assertTrue(result is AgentDecision.ObserveMore)
    }

    @Test fun dispatch_acceptance_without_state_change_is_not_success() {
        val before = CurrentActivityContext("pkg", null, "UNKNOWN", 1, 1, emptyList(), confidence = .8, timestamp = 1)
        assertEquals(false, AgentVerification.semanticStateChanged(before, before.copy(timestamp = 2), null))
    }

    @Test fun recovery_is_bounded_after_two_failures() {
        val task = AgentTask(originalUserRequest = "tap", interpretedGoal = AgentGoalType.TAP, expectedApp = "pkg", retryCount = 2)
        assertTrue(AgentTaskPolicy.nextAfterFailure(task, true) is AgentDecision.Fail)
    }
}
