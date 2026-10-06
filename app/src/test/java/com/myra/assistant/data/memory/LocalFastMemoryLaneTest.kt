package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFastMemoryLaneTest {
    @Test fun previewClassifiesOnlyConservativeReadOnlyRecall() {
        assertEquals(
            MemoryRecallType.PREFERENCES,
            LocalMemoryRecallRouter.classifyPreview("Mujhe kis tarah ke answers pasand hain?")?.type
        )
        assertEquals(
            MemoryRecallType.FRIENDS,
            LocalMemoryRecallRouter.classifyPreview("Mere dost kaun hain?")?.type
        )
        assertNull(LocalMemoryRecallRouter.classifyPreview("Mujhe short answers pasand hain."))
        assertNull(LocalMemoryRecallRouter.classifyPreview("Meri preference change karo."))
        assertNull(LocalMemoryRecallRouter.classifyPreview("Kal maine kya kiya?"))
    }

    @Test fun verifiedSpeechEquivalenceAllowsWrappersButRejectsChangedFacts() {
        assertTrue(VerifiedMemorySpeechEquivalence.matches("You prefer short answers.", "Prefers short answers."))
        assertTrue(VerifiedMemorySpeechEquivalence.matches("Kareem is your friend.", "Kareem tumhara dost hai."))
        assertFalse(VerifiedMemorySpeechEquivalence.matches("You prefer long answers.", "Prefers short answers."))
        assertFalse(VerifiedMemorySpeechEquivalence.matches("Kareem is not your friend.", "Kareem tumhara dost hai."))
    }
}
