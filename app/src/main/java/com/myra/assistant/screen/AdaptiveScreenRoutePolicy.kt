package com.myra.assistant.screen

/** Latest-frame-wins routing. Capture stays continuous; Gemini receives only useful states. */
internal class AdaptiveScreenRoutePolicy(
    private val changedMinIntervalMs: Long = 500L,
    private val staticKeepAliveMs: Long = 5_000L
) {
    private var lastSentAt = 0L
    private var dirty = true

    @Synchronized fun markDirty() { dirty = true }

    @Synchronized fun shouldRoute(now: Long, changed: Boolean, explicit: Boolean): Boolean {
        if (explicit) return true
        val dueToChange = (dirty || changed) && now - lastSentAt >= changedMinIntervalMs
        val keepAlive = lastSentAt == 0L || now - lastSentAt >= staticKeepAliveMs
        if (!dueToChange && !keepAlive) return false
        lastSentAt = now
        dirty = false
        return true
    }

    @Synchronized fun reset() { lastSentAt = 0L; dirty = true }
}
