package com.myra.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSpeechValidationPolicyTest {
    @Test
    fun memorySpeechUsesNaturalVoiceOnly() {
        assertEquals(2, LocalSpeechValidationPolicy.MEMORY.maxAttempts)
        assertTrue(LocalSpeechValidationPolicy.MEMORY.timeoutMs <= 4_000L)
        assertTrue(!LocalSpeechValidationPolicy.MEMORY.speakFallback)
        assertTrue(LocalSpeechValidationPolicy.MEMORY.trustBufferedNaturalAudio)
        assertTrue(LocalSpeechValidationPolicy.MEMORY.isolateFromMicDuringGeneration)
        assertTrue(!LocalSpeechValidationPolicy.MEMORY.bufferUntilValidated)
        assertTrue(LocalSpeechValidationPolicy.MEMORY.resumeMicImmediatelyAfterPlayback)
    }
}
