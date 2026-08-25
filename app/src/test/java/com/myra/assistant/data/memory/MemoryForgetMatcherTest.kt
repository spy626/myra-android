package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryForgetMatcherTest {
    @Test fun findsExactNameInsideSavedFact() {
        assertEquals("1", MemoryForgetMatcher.find("kareem", listOf(memory("1", "Kareem tumhara best friend hai")))?.id)
    }

    @Test fun findsUniqueOneLetterAsrNameError() {
        assertEquals("1", MemoryForgetMatcher.find("kareen", listOf(memory("1", "Kareem tumhara best friend hai")))?.id)
    }

    @Test fun findsUniqueKareemKarimAsrVariant() {
        assertEquals("1", MemoryForgetMatcher.find("karim", listOf(memory("1", "Zopy's best friend is Kareem")))?.id)
    }

    @Test fun refusesAmbiguousPhoneticNameVariant() {
        val memories = listOf(
            memory("1", "Zopy's best friend is Kareem"),
            memory("2", "Zopy knows Karam")
        )
        assertNull(MemoryForgetMatcher.find("karim", memories))
    }

    @Test fun refusesAmbiguousFuzzyNameError() {
        val memories = listOf(memory("1", "Kareem tumhara dost hai"), memory("2", "Kareen creator pasand hai"))
        assertEquals("2", MemoryForgetMatcher.find("kareen", memories)?.id)
        assertNull(MemoryForgetMatcher.find("kareel", memories))
    }

    @Test fun refusesShortFuzzyQuery() {
        assertNull(MemoryForgetMatcher.find("ram", listOf(memory("1", "Raj tumhara dost hai"))))
    }

    @Test fun findsLegacyMalformedPreferenceFromNaturalDeleteTarget() {
        val malformed = memory("1", "Zopy likes na ghumana")
        assertEquals("1", MemoryForgetMatcher.find("na ghumna", listOf(malformed))?.id)
    }

    private fun memory(id: String, fact: String) = MemoryEntity(
        id = id,
        stableKey = "person:$id",
        category = MemoryCategory.PERSON.name,
        fact = fact,
        normalizedFact = fact.lowercase(),
        sensitivity = MemorySensitivity.PERSONAL.name,
        confidence = 1.0,
        source = "test",
        createdAt = 1,
        updatedAt = 1,
        lastConfirmedAt = 1,
        active = true
    )
}
