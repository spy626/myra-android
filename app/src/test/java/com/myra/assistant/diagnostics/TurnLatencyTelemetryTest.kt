package com.myra.assistant.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnLatencyTelemetryTest {
    @Test fun `first accepted audio is write once per response generation`() {
        val logs = mutableListOf<String>()
        val telemetry = TurnLatencyTelemetry(logs::add)
        telemetry.begin(7, 10)
        telemetry.record(7, TurnLatencyTelemetry.Field.FIRST_ACCEPTED_MODEL_AUDIO, 100, generationId = 3)
        telemetry.record(7, TurnLatencyTelemetry.Field.FIRST_ACCEPTED_MODEL_AUDIO, 140, generationId = 3)
        assertEquals(100L, telemetry.snapshot(7)?.firstAcceptedModelAudioAt)
        assertEquals(1, logs.count { it.startsWith("FIRST_ACCEPTED_MODEL_AUDIO_AT") })
    }

    @Test fun `missing timestamps log NA and never negative sentinel`() {
        val logs = mutableListOf<String>()
        val telemetry = TurnLatencyTelemetry(logs::add)
        telemetry.begin(8, 10)
        telemetry.record(8, TurnLatencyTelemetry.Field.SPEECH_END, 100)
        telemetry.record(8, TurnLatencyTelemetry.Field.ACTION_RETURNED, 90)
        telemetry.logBreakdown(8, "TEST")
        assertTrue(logs.last().contains("speechEndToInputTurnMs=NA"))
        assertTrue(logs.last().contains("speechEndToVisibleActionEstimateMs=NA"))
        assertFalse(logs.last().contains("=-1"))
    }
}

