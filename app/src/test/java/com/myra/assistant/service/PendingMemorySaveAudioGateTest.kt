package com.myra.assistant.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMemorySaveAudioGateTest {
    @Test fun buffersOnlyFreshSameTurnModelAudio() {
        val gate = PendingMemorySaveAudioGate()
        assertTrue(gate.arm(7L, 10L))
        assertFalse(gate.capture(7L, 10L, byteArrayOf(1)))
        assertTrue(gate.capture(7L, 11L, byteArrayOf(2, 3)))
        assertTrue(gate.appendTranscript(7L, 11L, "Acha, noted."))
        val buffered = gate.take(7L)
        assertNotNull(buffered)
        assertEquals(11L, buffered!!.generationId)
        assertArrayEquals(byteArrayOf(2, 3), buffered.chunks.single())
        assertEquals("Acha, noted.", buffered.transcript)
        assertNull(gate.take(7L))
    }

    @Test fun dropsOversizedCandidateInsteadOfReleasingIt() {
        val gate = PendingMemorySaveAudioGate(maxBytes = 3)
        assertTrue(gate.arm(9L, 1L))
        assertTrue(gate.capture(9L, 2L, byteArrayOf(1, 2)))
        assertTrue(gate.capture(9L, 2L, byteArrayOf(3, 4)))
        assertNull(gate.take(9L))
    }
}
