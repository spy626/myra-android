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
        gate.onEnergyStarted()
        assertEquals(MediaAwareVadGate.Result.CONFIRMED_USER, gate.confirmFromTranscript())
        assertEquals(MediaAwareVadGate.Result.CONFIRMED_USER, gate.onEnergyEnded())
    }

    @Test fun staleTranscriptCannotConfirmWithoutAnActiveMediaCandidate() {
        assertEquals(MediaAwareVadGate.Result.NONE, MediaAwareVadGate().confirmFromTranscript())
    }
}
