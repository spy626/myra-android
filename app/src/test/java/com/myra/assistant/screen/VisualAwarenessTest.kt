package com.myra.assistant.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualAwarenessTest {
    @Test fun eye_off_never_allows_accessibility_screenshot() {
        assertFalse(VisualObservationPolicy.mayRequestScreenshot(false, 34))
    }

    @Test fun eye_on_uses_accessibility_screenshot_on_supported_android() {
        assertTrue(VisualObservationPolicy.mayRequestScreenshot(true, 30))
        assertFalse(VisualObservationPolicy.mayRequestScreenshot(true, 29))
    }

    @Test fun normal_visual_action_never_requires_media_projection() {
        assertFalse(VisualObservationPolicy.requiresMediaProjection(true))
    }

    @Test fun screen_mode_commands_remain_explicit_continuous_mode() {
        assertEquals(ScreenModeCommand.ON, ScreenModeCommandParser.parse("screen mode on karo"))
        assertEquals(ScreenModeCommand.OFF, ScreenModeCommandParser.parse("स्क्रीन शेयरिंग बंद करो"))
    }
}
