package com.myra.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSpeechValidationPolicyTest {
    @Test
    fun memorySpeechFailsAudiblyAndQuickly() {
        assertEquals(1, LocalSpeechValidationPolicy.MEMORY.maxAttempts)
        assertTrue(LocalSpeechValidationPolicy.MEMORY.timeoutMs <= 3_000L)
        assertTrue(LocalSpeechValidationPolicy.MEMORY.speakFallback)
    }
}
