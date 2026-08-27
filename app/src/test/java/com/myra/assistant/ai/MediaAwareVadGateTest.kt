package com.myra.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAwareVadGateTest {
    @Test fun sustainedMediaDoesNotBecomeUserSpeechWithoutAsrEvidence() {
        val gate = MediaAwareVadGate()
        assertEquals(MediaAwareVadGate.Result.POSSIBLE_MEDIA, gate.onEnergyStarted())
        assertEquals(MediaAwareVadGate.Result.REJECTED_MEDIA, gate.onEnergyEnded())
    }

    @Test fun meaningfulAsrEvidenceConfirmsRealUserOverMedia() {
        val gate = MediaAwareVadGate()
        gate.onEnergyStarted(now = 100, energy = .7f)
        assertEquals(MediaAwareVadGate.Result.CONFIRMED_USER, gate.confirmFromTranscript("Abhi kya dikh raha hai?"))
        assertEquals("Abhi kya dikh raha hai?", gate.transcriptEvidence)
        assertEquals(100, gate.startedAt)
        assertEquals(.7f, gate.peakEnergy, 0.001f)
        assertEquals(MediaAwareVadGate.Result.CONFIRMED_USER, gate.onEnergyEnded())
    }

    @Test fun staleTranscriptCannotConfirmWithoutAnActiveMediaCandidate() {
        assertEquals(MediaAwareVadGate.Result.NONE, MediaAwareVadGate().confirmFromTranscript())
    }
}
