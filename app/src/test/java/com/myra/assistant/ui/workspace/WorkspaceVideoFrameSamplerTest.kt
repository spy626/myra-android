package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceVideoFrameSamplerTest {
    @Test fun shortVideoYieldsEarlyMiddleLateTimestampSamples() {
        assertEquals(listOf(1_000_000L, 5_000_000L, 9_000_000L),
            WorkspaceVideoFrameSampler.sampleTimesUs(10_000L))
    }

    @Test fun unreadableOrVeryLongVideosFailBeforeEncoding() {
        assertTrue(runCatching { WorkspaceVideoFrameSampler.sampleTimesUs(0) }.isFailure)
        assertTrue(runCatching {
            WorkspaceVideoFrameSampler.sampleTimesUs(
                WorkspaceVideoFrameSampler.MAX_VIDEO_MS + 1L)
        }.isFailure)
    }
}
