package com.myra.assistant.service

import com.myra.assistant.data.memory.LocalRecallIntent
import com.myra.assistant.data.memory.MemoryRecallType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EarlyMemoryRecallGateTest {
    @Test fun releasesBufferedVoiceAsSoonAsRoomAndModelMeaningMatch() {
        val gate = EarlyMemoryRecallGate()
        val intent = LocalRecallIntent(MemoryRecallType.PREFERENCES, "Mujhe kis tarah ke answers pasand hain?", .99)
        assertTrue(gate.arm(7L, intent))
        assertTrue(gate.captureAudio(7L, 11L, byteArrayOf(1, 2, 3)))
        assertTrue(gate.markVerified(7L, "Prefers short answers."))
        assertNull(gate.takeVerifiedRelease(7L))
        assertTrue(gate.appendModelTranscript(7L, "You prefer short answers."))
        val release = gate.takeVerifiedRelease(7L)
        assertNotNull(release)
        assertEquals(11L, release!!.generationId)
        assertArrayEquals(byteArrayOf(1, 2, 3), release.chunks.single())
        assertTrue(gate.acceptsReleasedAudio(7L, 11L))
        assertNull(gate.takeVerifiedRelease(7L))
    }

    @Test fun toolGroundedAudioRejectsOldGenerationAndAcceptsNewGeneration() {
        val gate = EarlyMemoryRecallGate()
        val intent = LocalRecallIntent(MemoryRecallType.PREFERENCES, "My preference?", .99)
        assertTrue(gate.arm(9L, intent))
        assertTrue(gate.captureAudio(9L, 20L, byteArrayOf(1)))
        assertTrue(gate.authorizeToolGrounded(9L, 20L))
        assertFalse(gate.acceptToolGroundedAudio(9L, 20L))
        assertTrue(gate.acceptToolGroundedAudio(9L, 21L))
        assertTrue(gate.acceptToolGroundedAudio(9L, 21L))
        assertTrue(gate.wasReleased(9L))
    }

    @Test fun mismatchedOrInvalidatedSpeechIsNeverReleasedAsVerifiedMemory() {
        val gate = EarlyMemoryRecallGate()
        val intent = LocalRecallIntent(MemoryRecallType.PREFERENCES, "My preference?", .99)
        gate.arm(8L, intent)
        gate.captureAudio(8L, 12L, byteArrayOf(9))
        gate.appendModelTranscript(8L, "You prefer long answers.")
        gate.markVerified(8L, "Prefers short answers.")
        gate.markGenerationComplete(8L, 12L)
        assertNull(gate.takeVerifiedRelease(8L))
        gate.invalidatePreview(8L)
        assertNull(gate.takeVerifiedRelease(8L))
    }
}
