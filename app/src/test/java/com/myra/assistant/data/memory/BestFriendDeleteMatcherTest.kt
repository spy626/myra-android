package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class BestFriendDeleteMatcherTest {
    @Test fun deleteTargetsEveryDuplicateRowForCanonicalPerson() {
        val rows = listOf(
            memory("1", "Zopy's best friend is Karima", "person:best_friend:karima"),
            memory("2", "Zopy's best friend is Karima", "semantic:person:karima"),
            memory("3", "Zopy's best friend is Ayesha", "person:best_friend:ayesha")
        )
        assertEquals(setOf("1", "2"), BestFriendDeleteMatcher.findAll("Karima", rows).map { it.id }.toSet())
    }

    @Test fun phoneticDeleteTargetsCanonicalNaufalGroup() {
        val rows = listOf(
            memory("1", "Zopy's best friend is Naufal", "person:best_friend:naufal"),
            memory("2", "Zopy's best friend is Nopal", "person:best_friend:nopal")
        )
        assertEquals(setOf("1", "2"), BestFriendDeleteMatcher.findAll("Noval", rows).map { it.id }.toSet())
    }

    private fun memory(id: String, fact: String, key: String) = MemoryEntity(
        id = id,
        stableKey = key,
        category = MemoryCategory.PERSON.name,
        fact = fact,
        normalizedFact = fact.lowercase(),
        sensitivity = MemorySensitivity.PERSONAL.name,
        confidence = 0.96,
        source = "test",
        createdAt = 1,
        updatedAt = 1,
        lastConfirmedAt = 1,
        active = true
    )
}
