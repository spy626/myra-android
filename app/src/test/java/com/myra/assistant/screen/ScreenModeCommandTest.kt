package com.myra.assistant.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class ScreenModeCommandTest {
    @After fun clearPending() { PendingVisualActionStore.clear() }

    @Test fun screen_mode_commands_are_deterministic_and_unicode_safe() {
        assertEquals(ScreenModeCommand.ON, ScreenModeCommandParser.parse("screen sharing on karo"))
        assertEquals(ScreenModeCommand.ON, ScreenModeCommandParser.parse("स्क्रीन मोड ऑन करो"))
        assertEquals(ScreenModeCommand.OFF, ScreenModeCommandParser.parse("screen vision off karo"))
        assertEquals(ScreenModeCommand.OFF, ScreenModeCommandParser.parse("स्क्रीन शेयरिंग बंद करो"))
        assertNull(ScreenModeCommandParser.parse("screen ke bare mein batao"))
    }

    @Test fun accessibility_first_policy_only_requests_vision_when_needed() {
        assertEquals(VisualFallbackDecision.COMPLETE, AccessibilityFirstVisualPolicy.decide(true, false))
        assertEquals(VisualFallbackDecision.COMPLETE, AccessibilityFirstVisualPolicy.decide(true, true))
        assertEquals(VisualFallbackDecision.USE_ACTIVE_VISION, AccessibilityFirstVisualPolicy.decide(false, true))
        assertEquals(VisualFallbackDecision.REQUEST_PERMISSION, AccessibilityFirstVisualPolicy.decide(false, false))
    }

    @Test fun pending_action_survives_expected_permission_activity_and_resumes_once() {
        val action = pending(createdAt = 100)
        PendingVisualActionStore.replace(action)
        PendingVisualActionStore.markPermissionRequesting()
        assertNull(PendingVisualActionStore.cancelForIncompatiblePackage("com.myra.assistant"))
        PendingVisualActionStore.markPermissionApproved()
        assertEquals(action.command, PendingVisualActionStore.takeForResume("com.google.android.youtube", 200)?.command)
        assertNull(PendingVisualActionStore.takeForResume("com.google.android.youtube", 201))
    }

    @Test fun denial_timeout_and_real_app_switch_clear_pending_action() {
        PendingVisualActionStore.replace(pending(createdAt = 100))
        assertTrue(PendingVisualActionStore.clear() != null)

        PendingVisualActionStore.replace(pending(createdAt = 100))
        assertNull(PendingVisualActionStore.snapshot(100 + PendingVisualActionStore.TIMEOUT_MS + 1))

        PendingVisualActionStore.replace(pending(createdAt = 100))
        assertTrue(PendingVisualActionStore.cancelForIncompatiblePackage("com.android.chrome") != null)
        assertNull(PendingVisualActionStore.snapshot(101))
    }

    @Test fun a_new_pending_task_replaces_the_old_task() {
        PendingVisualActionStore.replace(pending(turnId = 1, createdAt = 100))
        val old = PendingVisualActionStore.replace(pending(turnId = 2, createdAt = 101))
        assertEquals(1L, old?.turnId)
        assertEquals(2L, PendingVisualActionStore.snapshot(102)?.turnId)
    }

    private fun pending(turnId: Long = 7, createdAt: Long) = PendingVisualAction(
        YouTubeSemanticCommand.Like,
        "com.google.android.youtube",
        turnId,
        4,
        9,
        createdAt
    )
}
