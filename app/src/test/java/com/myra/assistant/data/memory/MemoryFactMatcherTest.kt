package com.myra.assistant.data.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFactMatcherTest {
    private val candidate = PersonalMemoryExtractor.extract("Ayasa meri best friend hai")!!

    @Test fun matchesSameActiveFactWithoutCaseOrPunctuationNoise() {
        assertTrue(
            MemoryFactMatcher.isSameActiveFact(
                entity("ZOPY'S BEST FRIEND IS AYASA."),
                candidate
            )
        )
    }

    @Test fun differentReplacementStillNeedsPermission() {
        assertFalse(
            MemoryFactMatcher.isSameActiveFact(
                entity("Zopy's best friend is Karima"),
                candidate
            )
        )
    }

    @Test fun inactiveFactDoesNotSuppressPermission() {
        assertFalse(MemoryFactMatcher.isSameActiveFact(entity(candidate.fact, active = false), candidate))
    }

    private fun entity(fact: String, active: Boolean = true) = MemoryEntity(
        id = "memory-1",
        stableKey = "person:best_friend",
        category = MemoryCategory.PERSON.name,
        fact = fact,
        normalizedFact = fact.lowercase(),
        sensitivity = MemorySensitivity.PERSONAL.name,
        confidence = 0.96,
        source = "conversation",
        createdAt = 1L,
        updatedAt = 1L,
        lastConfirmedAt = 1L,
        active = active
    )
}
