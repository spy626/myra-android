package com.myra.assistant.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlledSpeechTimeoutGateTest {
    @Test fun firstAcceptedAudioCancelsNoAudioTimeoutForSameGeneration() {
        val gate = ControlledSpeechTimeoutGate()
        gate.start(41)
        assertTrue(gate.shouldFire(41))
        assertTrue(gate.acceptFirstAudio(41))
        assertFalse(gate.shouldFire(41))
    }

    @Test fun staleTimeoutCannotAffectNewGeneration() {
        val gate = ControlledSpeechTimeoutGate()
        gate.start(41)
        gate.start(42)
        assertFalse(gate.shouldFire(41))
        assertTrue(gate.shouldFire(42))
    }

    @Test fun successfulPlaybackCannotLaterBecomeNoAudioTimeout() {
        val gate = ControlledSpeechTimeoutGate()
        gate.start(9)
        gate.acceptFirstAudio(9)
        gate.clear(9)
        assertFalse(gate.shouldFire(9))
    }
}
