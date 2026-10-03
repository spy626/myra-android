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

    @Test fun rejectsShortPartialNaturalAudio() {
        assertFalse(
            LocalSpeechGate.hasEnoughBufferedNaturalAudio(
                audioBytes = 12_000,
                expected = "Ayasa tumhari best friend hai, main yeh yaad rakhun?"
            )
        )
    }

    @Test fun acceptsCompleteEnoughNaturalAudio() {
        assertTrue(
            LocalSpeechGate.hasEnoughBufferedNaturalAudio(
                audioBytes = 80_000,
                expected = "Ayasa tumhari best friend hai, main yeh yaad rakhun?"
            )
        )
    }

    @Test fun exactMatchRequiresTheWholePreparedReply() {
        assertFalse(LocalSpeechGate.matchesExpectedExactly("Ayasa tumhari", "Ayasa tumhari best friend hai"))
        assertTrue(LocalSpeechGate.matchesExpectedExactly("Ayasa tumhari best friend hai.", "Ayasa tumhari best friend hai"))
    }

    @Test fun acceptsConservativeRomanizationVariation() {
        assertTrue(LocalSpeechGate.semanticallyEquivalent(
            "Screen vijana abhi active nahim hai",
            "Screen Vision abhi active nahi hai"
        ))
    }

    @Test fun rejectsUnrelatedControlledSpeech() {
        assertFalse(LocalSpeechGate.semanticallyEquivalent(
            "YouTube par ek video chal raha hai",
            "Screen Vision abhi active nahi hai"
        ))
    }

    @Test fun bufferedMemoryReplyWaitsForCompleteGeminiTurn() {
        assertFalse(
            LocalSpeechGate.shouldReleaseBeforeTurnComplete(
                bufferUntilTurnComplete = true,
                actual = "Theek hai save nahi karungi",
                expected = "Theek hai, save nahi karungi."
            )
        )
        assertTrue(
            LocalSpeechGate.shouldReleaseBeforeTurnComplete(
                bufferUntilTurnComplete = false,
                actual = "YouTube close",
                expected = "YouTube close kar diya."
            )
        )
    }
}
