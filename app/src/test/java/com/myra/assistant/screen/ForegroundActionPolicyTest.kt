package com.myra.assistant.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundActionPolicyTest {
    private fun context(packageName: String, windowId: Int = 7, generation: Long = 3) =
        ForegroundAppContext(packageName, windowId = windowId, generation = generation, observedAt = 100L)

    @Test
    fun chrome_foreground_implicit_action_stays_in_chrome() {
        assertEquals(
            "com.android.chrome",
            ForegroundActionPolicy.destinationPackage("com.android.chrome", null)
        )
        assertFalse(ForegroundActionPolicy.mayLaunchDifferentApp(null))
    }

    @Test
    fun stale_youtube_context_cannot_override_current_chrome() {
        val oldScope = ForegroundActionPolicy.scope(context("com.google.android.youtube"))!!
        assertFalse(ForegroundActionPolicy.canExecute(oldScope, context("com.android.chrome")))
    }

    @Test
    fun package_or_window_change_after_resolution_rejects_action() {
        val scope = ForegroundActionPolicy.scope(context("com.android.chrome"))!!
        assertFalse(ForegroundActionPolicy.canExecute(scope, context("com.android.chrome", windowId = 8)))
        assertFalse(ForegroundActionPolicy.canExecute(scope, context("com.android.chrome", generation = 4)))
    }

    @Test
    fun exact_current_window_allows_action() {
        val current = context("com.android.chrome")
        val scope = ForegroundActionPolicy.scope(current)!!
        assertTrue(ForegroundActionPolicy.canExecute(scope, current))
    }

    @Test
    fun explicit_cross_app_request_is_the_only_launch_permission() {
        assertEquals(
            "com.google.android.youtube",
            ForegroundActionPolicy.destinationPackage(
                "com.android.chrome",
                "com.google.android.youtube"
            )
        )
        assertTrue(ForegroundActionPolicy.mayLaunchDifferentApp("com.google.android.youtube"))
        assertNull(ForegroundActionPolicy.destinationPackage(null, null))
    }
}
