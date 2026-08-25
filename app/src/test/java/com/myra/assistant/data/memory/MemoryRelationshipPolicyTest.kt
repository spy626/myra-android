package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryRelationshipPolicyTest {
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
}
