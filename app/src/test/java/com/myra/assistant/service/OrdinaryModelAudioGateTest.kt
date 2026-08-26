package com.myra.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Test

class OrdinaryModelAudioGateTest {
    @Test fun audioFromGenerationActiveAtUserSpeechStartIsDropped() {
        val gate = OrdinaryModelAudioGate()
        gate.onSpeechActivityStarted(latestGenerationId = 8)
        assertEquals(ModelAudioDecision.DROP_STALE_GENERATION, gate.decide(8))
    }

    @Test fun generationStartAloneCannotCompleteSpeechOrPlay() {
        val gate = OrdinaryModelAudioGate()
        gate.onSpeechActivityStarted(latestGenerationId = 8)
        assertEquals(ModelAudioDecision.BUFFER_UNTIL_SPEECH_END, gate.decide(9))
        assertEquals(ModelAudioDecision.BUFFER_UNTIL_SPEECH_END, gate.decide(9))
    }

    @Test fun exactlyOneGenerationOwnsOrdinaryReplyForLogicalTurn() {
        val gate = OrdinaryModelAudioGate()
        gate.onSpeechActivityStarted(latestGenerationId = 2)
        gate.onSpeechActivityEnded()
        assertEquals(ModelAudioDecision.ACCEPT, gate.decide(3))
        assertEquals(ModelAudioDecision.ACCEPT, gate.decide(3))
        assertEquals(ModelAudioDecision.DROP_DUPLICATE_GENERATION, gate.decide(4))
    }

    @Test fun speechEndReleasesCurrentGenerationWithoutQuarantine() {
        val gate = OrdinaryModelAudioGate()
        gate.onSpeechActivityStarted(latestGenerationId = 5)
        assertEquals(ModelAudioDecision.BUFFER_UNTIL_SPEECH_END, gate.decide(6))
        gate.onSpeechActivityEnded()
        assertEquals(ModelAudioDecision.ACCEPT, gate.decide(6))
    }

    @Test fun cancelledGenerationNeverResumesAfterBargeIn() {
        val gate = OrdinaryModelAudioGate()
        assertEquals(ModelAudioDecision.ACCEPT, gate.decide(4))
        gate.cancelGeneration(4)
        assertEquals(ModelAudioDecision.DROP_CANCELLED_GENERATION, gate.decide(4))
    }
}
