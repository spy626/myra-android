package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun colloquialHinglishPreferenceGroundsToSameCanonicalMeaning() {
        val candidate = SemanticMemoryProposalValidator.validate(
            fact = "Zopy likes coding",
            categoryName = "PREFERENCE",
            memoryKey = "coding_interest",
            evidence = "mujhe na codes karne accha lagta hai",
            confidence = 0.94,
            conversationContext = "Mujhe na codes karne accha lagta hai"
        )!!
        assertEquals("Zopy likes coding", candidate.fact)
        assertEquals("preference:likes:coding", candidate.stableKey)
        assertEquals(MemoryProvenance.GEMINI_GROUNDED_PROPOSAL, candidate.provenance)
    }

    @Test fun personPreferenceIsAttributedToNamedPersonNotUser() {
        val candidate = SemanticMemoryProposalValidator.validate(
            fact = "Kareem likes coding",
            categoryName = "PERSON",
            memoryKey = "kareem_coding",
            evidence = "Kareem ko coding bhi pasand hai",
            confidence = 0.94,
            conversationContext = "Kareem ko coding bhi pasand hai"
        )!!
        assertEquals("Kareem likes coding", candidate.fact)
        assertEquals("person:kareem:preference:coding", candidate.stableKey)
        assertEquals("Kareem", candidate.entityName)
        assertEquals(NaturalMemoryExtractor.stablePersonId("Kareem"), candidate.entityId)
        assertFalse(candidate.fact.startsWith("Zopy likes"))
    }

    @Test fun wrongSubjectAttributionIsRejectedEvenWhenTopicMatches() {
        assertNull(SemanticMemoryProposalValidator.validate(
            fact = "Zopy likes coding",
            categoryName = "PREFERENCE",
            memoryKey = "coding_interest",
            evidence = "Kareem ko coding pasand hai",
            confidence = 0.96,
            conversationContext = "Kareem ko coding pasand hai"
        ))
    }

    @Test fun uncertaintyAndSpeculationNeverBecomePersonMemory() {
        assertNull(SemanticMemoryProposalValidator.validate(
            fact = "Kareem likes coding",
            categoryName = "PERSON",
            memoryKey = "kareem_coding",
            evidence = "Mereko lagta hai Kareem ko coding pasand hogi",
            confidence = 0.96,
            conversationContext = "Mereko lagta hai Kareem ko coding pasand hogi"
        ))
    }

    @Test fun preferencePolarityMustMatchActualUserEvidence() {
        assertNull(SemanticMemoryProposalValidator.validate(
            fact = "Zopy likes coding",
            categoryName = "PREFERENCE",
            memoryKey = "coding_interest",
            evidence = "Mujhe coding accha nahi lagta hai",
            confidence = 0.96,
            conversationContext = "Mujhe coding accha nahi lagta hai"
        ))
        assertNull(SemanticMemoryProposalValidator.validate(
            fact = "Zopy does not like coding",
            categoryName = "PREFERENCE",
            memoryKey = "coding_interest",
            evidence = "Mujhe coding accha lagta hai",
            confidence = 0.96,
            conversationContext = "Mujhe coding accha lagta hai"
        ))
        val negative = SemanticMemoryProposalValidator.validate(
            fact = "Zopy does not like web development",
            categoryName = "PREFERENCE",
            memoryKey = "web_development",
            evidence = "Web development mujhe utna pasand nahi hai",
            confidence = 0.94,
            conversationContext = "Web development mujhe utna pasand nahi hai"
        )!!
        assertEquals("Zopy does not like web development", negative.fact)
    }

    @Test fun temporaryPreferenceIsNotPromotedToLongTermMemory() {
        assertNull(SemanticMemoryProposalValidator.validate(
            fact = "Zopy likes coding",
            categoryName = "PREFERENCE",
            memoryKey = "coding_interest",
            evidence = "Aaj mujhe coding accha lagta hai",
            confidence = 0.94,
            conversationContext = "Aaj mujhe coding accha lagta hai"
        ))
    }

    @Test fun acceptsGroundedWorkflowForSilentLearning() {
        val candidate = SemanticMemoryProposalValidator.validate(
            fact = "Zopy tests Android releases on his phone",
            categoryName = "workflow",
            memoryKey = "android_release_testing",
            evidence = "I test Android releases on my phone",
            confidence = 0.92,
            conversationContext = "I always test Android releases on my phone"
        )!!

        assertEquals(MemoryCategory.WORKFLOW, candidate.category)
        assertEquals(MemorySensitivity.LOW, candidate.sensitivity)
        assertEquals(MemorySaveDecision.AUTO_SAVE, MemorySafetyPolicy.decide(candidate))
    }

    @Test fun groundedHinglishRecurringActivityCanBecomeWorkflow() {
        val candidate = SemanticMemoryProposalValidator.validate(
            fact = "Zopy usually builds Android apps",
            categoryName = "WORKFLOW",
            memoryKey = "android_app_building",
            evidence = "Main mostly Android apps banata hoon",
            confidence = 0.91,
            conversationContext = "Main mostly Android apps banata hoon"
        )
        assertEquals(MemoryCategory.WORKFLOW, candidate?.category)
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
