package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisMemoryCanonicalizerTest {
    @Test fun kareemAndKarimResolveToSamePhoneticPerson() {
        assertTrue(JarvisMemoryCanonicalizer.personEquivalent("Kareem", "Karim"))
        assertEquals(
            JarvisMemoryCanonicalizer.personSignature("Kareem"),
            JarvisMemoryCanonicalizer.personSignature("Karim")
        )
        assertFalse(JarvisMemoryCanonicalizer.personEquivalent("Kareem", "Naufal"))
    }

    @Test fun duplicateTravelWordingMatchesButDifferentTripDoesNot() {
        assertTrue(
            JarvisMemoryCanonicalizer.episodesSimilar(
                "Kareem ke satha ghumane gaya manali kerala",
                "Kareem ke sath ghumne gaya tha Manali and Kerala."
            )
        )
        assertFalse(
            JarvisMemoryCanonicalizer.episodesSimilar(
                "Kareem ke sath Manali gaya tha",
                "Kareem ke sath Kerala gaya tha"
            )
        )
    }

    @Test fun garbledShortAnswerPreferenceCanonicalizes() {
        assertEquals(
            "short answers",
            JarvisMemoryCanonicalizer.canonicalPreference("sorta ansara")
        )
    }
}
