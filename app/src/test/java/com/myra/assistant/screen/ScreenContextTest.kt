package com.myra.assistant.screen

import org.junit.Assert.*
import org.junit.Test

class ScreenContextTest {
    @Test fun `screen context remains temporary and invalidates with session`() {
        ScreenContextStore.invalidate()
        val frame = ScreenFrame("s1", 1L, 100L, 110L, "hash", byteArrayOf(1), "test", 100, 200)
        ScreenContextStore.onFrame(frame)
        ScreenContextStore.onAccessibility(
            "s1", "com.browser", "Browser", emptyList(), 120L
        )
        val context = ScreenContextStore.snapshot()
        assertEquals("s1", context.screenSessionId)
        assertEquals("com.browser", context.currentPackage)
        assertEquals(1L, context.frameId)
        ScreenContextStore.invalidate()
        assertEquals(ScreenContext(), ScreenContextStore.snapshot())
    }
}
