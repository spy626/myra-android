package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticMemoryProposalValidatorTest {
    @Test fun acceptsGroundedRelationshipForAutomaticLearning() {
        val candidate = SemanticMemoryProposalValidator.validate(
            fact = "Kareem is Zopy's male best friend",
            categoryName = "PERSON",
            memoryKey = "best_friend",
            evidence = "male best friend uska naam Kareem hai",
            confidence = 0.94,
            conversationContext = "Mera ek male best friend hai uska naam Kareem hai woh bahut accha dost hai"
        )!!
        assertEquals("person:best_friend", candidate.stableKey)
        assertEquals(MemorySensitivity.PERSONAL, candidate.sensitivity)
        assertEquals(MemorySaveDecision.AUTO_SAVE, MemorySafetyPolicy.decide(candidate))
    }

    @Test fun acceptsSafePreferenceForSilentLearning() {
        val candidate = SemanticMemoryProposalValidator.validate(
            fact = "Zopy likes horror movies",
            categoryName = "preference",
            memoryKey = "movie_genre",
            evidence = "mujhe horror movie bahut pasand hai",
            confidence = 0.91,
            conversationContext = "Mujhe horror movie bahut pasand hai"
        )!!
        assertEquals(MemorySensitivity.LOW, candidate.sensitivity)
        assertTrue(MemorySafetyPolicy.decide(candidate) == MemorySaveDecision.AUTO_SAVE)
    }

    @Test fun rejectsHallucinatedOrWeaklyGroundedFact() {
        assertNull(SemanticMemoryProposalValidator.validate(
            fact = "Zopy wants to move to Japan",
            categoryName = "GOAL",
            memoryKey = "relocation_goal",
            evidence = "Japan",
            confidence = 0.95,
            conversationContext = "I watched a video about Japan"
        ))
    }

    @Test fun rejectsSecretsEvenWhenSpoken() {
        assertNull(SemanticMemoryProposalValidator.validate(
            fact = "Zopy's password is secret123",
            categoryName = "IDENTITY",
            memoryKey = "password",
            evidence = "my password is secret123",
            confidence = 0.99,
            conversationContext = "My password is secret123"
        ))
    }

    @Test fun rejectsMalformedTravelProposalObservedFromCorruptedAsr() {
        assertNull(SemanticMemoryProposalValidator.validate(
            fact = "Zopy likes to re-visit travel destinations",
            categoryName = "PREFERENCE",
            memoryKey = "travel_preference",
            evidence = "Goom naam hai main re-visit karta hun",
            confidence = 0.93,
            conversationContext = "Goom naam hai main re-visit karta hun"
        ))
    }

    @Test fun visitingMunnarDoesNotBecomeAPreference() {
        assertNull(SemanticMemoryProposalValidator.validate(
            fact = "Zopy likes Munnar",
            categoryName = "PREFERENCE",
            memoryKey = "travel_destination",
            evidence = "Main Munnar mein bahut jagah ja ke aaya hoon",
            confidence = 0.95,
            conversationContext = "Main Munnar mein bahut jagah ja ke aaya hoon"
        ))
    }

    @Test fun explicitMunnarPreferenceIsStillAccepted() {
        val candidate = SemanticMemoryProposalValidator.validate(
            fact = "Zopy likes Munnar",
            categoryName = "PREFERENCE",
            memoryKey = "travel_destination",
            evidence = "Mujhe Munnar bahut pasand hai",
            confidence = 0.95,
            conversationContext = "Mujhe Munnar bahut pasand hai"
        )
        assertEquals("Zopy likes Munnar", candidate?.fact)
    }
}
