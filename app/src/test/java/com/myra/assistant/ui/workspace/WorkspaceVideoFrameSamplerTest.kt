package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceVideoFrameSamplerTest {
    @Test fun tenUniformSamplesSpanVideoInPlaybackOrder() {
        assertEquals(10, WorkspaceVideoFrameSampler.MAX_FRAMES)
        assertEquals((0..9).map { 500_000L + it * 1_000_000L },
            WorkspaceVideoFrameSampler.sampleTimesUs(10_000L))
        val times = WorkspaceVideoFrameSampler.sampleTimesUs(
            WorkspaceVideoFrameSampler.MAX_VIDEO_MS)
        assertEquals(10, times.size)
        assertTrue(times.zipWithNext().all { (a, b) -> a < b })
        assertEquals(15_000_000L, times.first())
        assertEquals(285_000_000L, times.last())
    }

    @Test fun unreadableOrLongVideoIsRejected() {
        assertTrue(runCatching { WorkspaceVideoFrameSampler.sampleTimesUs(0) }.isFailure)
        assertTrue(runCatching {
            WorkspaceVideoFrameSampler.sampleTimesUs(
                WorkspaceVideoFrameSampler.MAX_VIDEO_MS + 1L)
        }.isFailure)
    }
}
