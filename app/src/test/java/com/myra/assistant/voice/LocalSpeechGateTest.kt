package com.myra.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSpeechGateTest {
    private val expected =
        "Aapke liye YouTube close kar diya, jaan. Ab theek hai? Aur kuch karun?"

    @Test fun acceptsMatchingTwoWordPrefix() {
        assertTrue(LocalSpeechGate.matchesExpectedPrefix("Aapke liye", expected))
    }

    @Test fun acceptsLongerMatchingPrefix() {
        assertTrue(LocalSpeechGate.matchesExpectedPrefix("Aapke liye YouTube close", expected))
    }

    @Test fun blocksGenericAcknowledgements() {
        assertFalse(LocalSpeechGate.matchesExpectedPrefix("OK", expected))
        assertFalse(LocalSpeechGate.matchesExpectedPrefix("Okay", expected))
    }

    @Test fun blocksOneWordAndWrongPrefixes() {
        assertFalse(LocalSpeechGate.matchesExpectedPrefix("Aapke", expected))
        assertFalse(LocalSpeechGate.matchesExpectedPrefix("Lo jaan", expected))
    }
}
