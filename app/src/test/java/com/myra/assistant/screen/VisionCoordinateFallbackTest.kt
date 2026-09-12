package com.myra.assistant.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionCoordinateFallbackTest {
    @Test fun safeFreshHighConfidenceYouTubeTargetIsAllowed() {
        val result = VisionCoordinateSafetyPolicy.evaluate(
            "com.google.android.youtube", 520, 180, 0.94, 700,
            listOf("Search", "Home", "Subscriptions")
        )
        assertTrue(result.allowed)
        assertEquals("allowed", result.reason)
    }

    @Test fun lowConfidenceStaleProtectedAndSensitiveTargetsAreBlocked() {
        assertFalse(VisionCoordinateSafetyPolicy.evaluate(
            "com.google.android.youtube", 500, 500, 0.70, 200, listOf("Search")
        ).allowed)
        assertFalse(VisionCoordinateSafetyPolicy.evaluate(
            "com.google.android.youtube", 500, 500, 0.95, 8_000, listOf("Search")
        ).allowed)
        assertFalse(VisionCoordinateSafetyPolicy.evaluate(
            "com.android.settings", 500, 500, 0.95, 200, listOf("Wi-Fi")
        ).allowed)
        assertFalse(VisionCoordinateSafetyPolicy.evaluate(
            "com.example.app", 500, 500, 0.95, 200, listOf("Confirm payment")
        ).allowed)
    }

    @Test fun searchBarCommandsRouteToVisualActionAndSearchField() {
        assertEquals(
            ScreenVisionIntent.CONTROL_TARGET,
            ScreenVisionIntentParser.parse("YouTube search bar pe click karo")
        )
        assertEquals(
            FastVisualKind.ACTION,
            FastVisualRequestClassifier.classify("YouTube search bar pe click karo")?.kind
        )
        val result = ScreenTargetResolver.resolve(
            listOf(
                ScreenTargetCandidate(1, "Search", "search_button", 900, 20, 1000, 130),
                ScreenTargetCandidate(2, "Search YouTube", "search_field", 80, 70, 850, 170)
            ),
            "search bar", null, null, 1080, 2400
        )
        assertTrue(result is ScreenTargetResolution.Selected)
        assertEquals(2, (result as ScreenTargetResolution.Selected).candidate.id)
    }
}
