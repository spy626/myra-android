package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryRelationshipPolicyTest {
    @Test fun ordinarySavesUsePersonSpecificKeysAndConfirmedReplacementUsesSharedKey() {
        val candidate = MemoryCandidate(
            category = MemoryCategory.PERSON,
            fact = "Zopy's best friend is Ayesha",
            stableKey = "person:best_friend",
            sensitivity = MemorySensitivity.PERSONAL,
            confidence = 0.96
        )
        assertEquals(
            "person:best_friend:ayesha",
            MemoryRelationshipPolicy.canonicalizeForSave(candidate, replaceExisting = false).stableKey
        )
        assertEquals(
            "person:best_friend",
            MemoryRelationshipPolicy.canonicalizeForSave(candidate, replaceExisting = true).stableKey
        )
    }

    @Test fun recognizesAndCanonicalizesLegacyModelFact() {
        assertEquals("Naufal", MemoryRelationshipPolicy.personName("The user's best friend is Naufal"))
    }

    @Test fun semanticAndDeterministicBestFriendFactsShareOneKey() {
        val candidate = MemoryCandidate(
            category = MemoryCategory.PERSON,
            fact = "Kareem is Zopy's male best friend",
            stableKey = "semantic:person:best_friend",
            sensitivity = MemorySensitivity.PERSONAL,
            confidence = 0.95
        )
        assertEquals("person:best_friend", MemoryRelationshipPolicy.canonicalize(candidate).stableKey)
    }

    @Test fun extractsPersonFromBothSupportedFactShapes() {
        assertEquals("Karima", MemoryRelationshipPolicy.personName("Zopy's best friend is Karima"))
        assertEquals("Kareem", MemoryRelationshipPolicy.personName("Kareem is Zopy's male best friend"))
    }

    @Test fun canonicalizesNaturalHinglishReplacementFact() {
        val raw = MemoryCandidate(
            category = MemoryCategory.PERSON,
            fact = "Ayesha meri best friend hai",
            stableKey = "person:ayesha_best_friend",
            sensitivity = MemorySensitivity.PERSONAL,
            confidence = 1.0,
            explicitlyRequested = true
        )
        val canonical = MemoryRelationshipPolicy.canonicalize(raw)
        assertEquals("person:best_friend", canonical.stableKey)
        assertEquals("Zopy's best friend is Ayesha", canonical.fact)
    }

    @Test fun additionalBestFriendUsesPersonSpecificStableKey() {
        val raw = MemoryCandidate(
            category = MemoryCategory.PERSON,
            fact = "Ayesha meri best friend hai",
            stableKey = "person:ayesha_best_friend",
            sensitivity = MemorySensitivity.PERSONAL,
            confidence = 1.0
        )
        val additional = MemoryRelationshipPolicy.canonicalizeAdditional(raw)
        assertEquals("person:best_friend:ayesha", additional.stableKey)
        assertEquals("Zopy's best friend is Ayesha", additional.fact)
    }
}
