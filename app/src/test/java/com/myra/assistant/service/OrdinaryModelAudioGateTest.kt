package com.myra.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Test

class OrdinaryModelAudioGateTest {
    @Test fun audioFromGenerationActiveAtUserSpeechStartIsDropped() {
        val gate = OrdinaryModelAudioGate()
        gate.onInputTurnStarted(latestGenerationId = 8)
        assertEquals(ModelAudioDecision.DROP_STALE_GENERATION, gate.decide(8))
    }

    @Test fun nextProtocolGenerationStartsWithoutFixedQuarantine() {
        val gate = OrdinaryModelAudioGate()
        gate.onInputTurnStarted(latestGenerationId = 8)
        assertEquals(ModelAudioDecision.ACCEPT, gate.decide(9))
    }

    @Test fun exactlyOneGenerationOwnsOrdinaryReplyForLogicalTurn() {
        val gate = OrdinaryModelAudioGate()
        gate.onInputTurnStarted(latestGenerationId = 2)
        assertEquals(ModelAudioDecision.ACCEPT, gate.decide(3))
        assertEquals(ModelAudioDecision.ACCEPT, gate.decide(3))
        assertEquals(ModelAudioDecision.DROP_DUPLICATE_GENERATION, gate.decide(4))
    }

    @Test fun committedTurnDoesNotDelayFutureUncontestedAudio() {
        val gate = OrdinaryModelAudioGate()
        gate.onInputTurnStarted(latestGenerationId = 5)
        gate.onInputTurnCommitted()
        assertEquals(ModelAudioDecision.ACCEPT, gate.decide(6))
    }
}
