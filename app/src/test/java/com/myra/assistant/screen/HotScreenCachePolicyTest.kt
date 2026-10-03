package com.myra.assistant.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotScreenCachePolicyTest {
    private fun context(packageName: String = "com.browser", snapshotAt: Long = 1_000L, scrollAt: Long = 0L) =
        ScreenContext(
            screenSessionId = "session",
            currentPackage = packageName,
            frameTimestamp = snapshotAt,
            accessibilityTimestamp = snapshotAt,
            summary = ScreenSummary(packageName = packageName, builtAt = snapshotAt),
            lastScrollAt = scrollAt
        )

    @Test fun `static screen question cache remains valid for 1500 milliseconds`() {
        assertTrue(HotScreenCachePolicy.isFresh(context(), "session", 2_500L, ScreenCacheUse.QUESTION))
        assertFalse(HotScreenCachePolicy.isFresh(context(), "session", 2_501L, ScreenCacheUse.QUESTION))
    }

    @Test fun `scroll and video caches use shorter validity`() {
        assertEquals(500L, HotScreenCachePolicy.maxAgeMs("com.browser", 1_800L, 2_000L, ScreenCacheUse.QUESTION))
        assertEquals(300L, HotScreenCachePolicy.maxAgeMs("com.google.android.youtube", 0L, 2_000L, ScreenCacheUse.QUESTION))
    }

    @Test fun `actions use at most 700 millisecond static context`() {
        assertTrue(HotScreenCachePolicy.isFresh(context(), "session", 1_700L, ScreenCacheUse.ACTION))
        assertFalse(HotScreenCachePolicy.isFresh(context(), "session", 1_701L, ScreenCacheUse.ACTION))
    }

    @Test fun `cache cannot cross screen sessions`() {
        assertFalse(HotScreenCachePolicy.isFresh(context(), "new-session", 1_100L, ScreenCacheUse.QUESTION))
    }
}
