package com.myra.assistant.diagnostics

data class ScreenContextLoadSnapshot(
    val windowMs: Long,
    val eventsReceived: Int,
    val refreshRequested: Int,
    val refreshExecuted: Int,
    val refreshCoalesced: Int,
    val mainThreadRefreshDurationMs: Long,
    val semanticTreeBuildDurationMs: Long
)

/** One-second passive counters; this class never schedules or suppresses refreshes. */
class ScreenContextLoadTelemetry(
    private val clock: () -> Long,
    private val log: (String) -> Unit
) {
    private var windowStartedAt = clock()
    private var events = 0
    private var requested = 0
    private var executed = 0
    private var coalesced = 0
    private var refreshDuration = 0L
    private var treeDuration = 0L

    @Synchronized fun eventReceived() { events++; emitIfDue() }
    @Synchronized fun refreshRequested() { requested++; emitIfDue() }
    @Synchronized fun refreshExecuted(mainMs: Long, treeMs: Long, wasCoalesced: Boolean) {
        executed++
        if (wasCoalesced) coalesced++
        refreshDuration += mainMs.coerceAtLeast(0L)
        treeDuration += treeMs.coerceAtLeast(0L)
        emitIfDue()
    }

    @Synchronized fun snapshot(): ScreenContextLoadSnapshot = ScreenContextLoadSnapshot(
        (clock() - windowStartedAt).coerceAtLeast(0L), events, requested, executed, coalesced,
        refreshDuration, treeDuration
    )

    private fun emitIfDue() {
        val now = clock()
        val elapsed = now - windowStartedAt
        if (elapsed < 1_000L) return
        log(
            "SCREEN_CONTEXT_LOAD windowMs=${elapsed.coerceAtLeast(0L)} " +
                "eventsReceivedPerSecond=$events refreshRequestedPerSecond=$requested " +
                "refreshExecutedPerSecond=$executed refreshCoalescedPerSecond=$coalesced " +
                "mainThreadRefreshDurationMs=$refreshDuration semanticTreeBuildDurationMs=$treeDuration"
        )
        windowStartedAt = now
        events = 0; requested = 0; executed = 0; coalesced = 0
        refreshDuration = 0L; treeDuration = 0L
    }
}

