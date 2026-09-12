package com.myra.assistant.screen

/** Bounds expensive event rebuilds, not user-requested fresh observations. */
class ScreenRefreshGate {
    private var pending = false
    private var lastCompletedAt: Long? = null
    @Synchronized fun requestEvent(now: Long): Long? {
        if (pending) return null
        pending = true
        return maxOf(DEBOUNCE_MS, lastCompletedAt?.let { MIN_INTERVAL_MS - (now - it) } ?: 0L)
    }
    @Synchronized fun started() { pending = false }
    @Synchronized fun completed(now: Long) { lastCompletedAt = now }
    @Synchronized fun watcherNeeded(now: Long, intervalMs: Long): Boolean =
        !pending && lastCompletedAt?.let { now - it >= intervalMs } != false
    companion object {
        const val DEBOUNCE_MS = 150L
        const val MIN_INTERVAL_MS = 250L
    }
}
