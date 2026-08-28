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
}
