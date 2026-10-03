package com.myra.assistant.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContextLoadTelemetryTest {
    @Test fun `reports event refresh and coalescing load without changing scheduling`() {
        var now = 0L
        val logs = mutableListOf<String>()
        val telemetry = ScreenContextLoadTelemetry({ now }, logs::add)
        telemetry.eventReceived()
        telemetry.refreshRequested()
        telemetry.refreshExecuted(mainMs = 12, treeMs = 8, wasCoalesced = true)
        now = 1_001
        telemetry.eventReceived()
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("refreshCoalescedPerSecond=1"))
        assertTrue(logs.single().contains("semanticTreeBuildDurationMs=8"))
    }
}

