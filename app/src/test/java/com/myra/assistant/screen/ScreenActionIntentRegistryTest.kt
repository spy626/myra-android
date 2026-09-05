package com.myra.assistant.screen

import org.junit.Assert.*
import org.junit.Test

class ScreenActionIntentRegistryTest {
    private fun create(registry: ScreenActionIntentRegistry, turn: Long, session: String, frame: Long) =
        registry.create(turn, session, "open video", "video", "center", null, "youtube", 10L, frame, 0.9)

    @Test fun `new command invalidates older unresolved action`() {
        val registry = ScreenActionIntentRegistry()
        val old = create(registry, 25L, "s1", 4L)
        val newer = create(registry, 26L, "s1", 5L)
        assertFalse(registry.isCurrent(old.actionId, 25L, "s1"))
        assertTrue(registry.isCurrent(newer.actionId, 26L, "s1"))
    }

    @Test fun `old screen session cannot execute in new session`() {
        val registry = ScreenActionIntentRegistry()
        val action = create(registry, 25L, "old", 4L)
        assertFalse(registry.isCurrent(action.actionId, 25L, "new"))
    }

    @Test
    fun foreground_change_rejects_resolved_action() {
        val registry = ScreenActionIntentRegistry()
        val action = registry.create(
            turnId = 9L,
            screenSessionId = "screen-a",
            requestedText = "click that one",
            target = "that one",
            position = null,
            ordinal = null,
            appPackage = "com.android.chrome",
            resolvedAt = 100L,
            sourceFrameId = 4L,
            confidence = 0.9,
            activeWindowId = 7,
            screenContextGeneration = 3L
        )
        assertTrue(
            registry.isExecutable(
                action.actionId,
                9L,
                "screen-a",
                ForegroundAppContext("com.android.chrome", windowId = 7, generation = 3L, observedAt = 101L)
            )
        )
        assertFalse(
            registry.isExecutable(
                action.actionId,
                9L,
                "screen-a",
                ForegroundAppContext("com.android.chrome", windowId = 8, generation = 4L, observedAt = 102L)
            )
        )
    }
}
