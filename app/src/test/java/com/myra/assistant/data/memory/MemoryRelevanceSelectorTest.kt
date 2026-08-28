package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRelevanceSelectorTest {
    private fun memory(id: String, key: String, fact: String, updatedAt: Long, active: Boolean = true) =
        MemoryEntity(
            id, key, MemoryCategory.PREFERENCE.name, fact, fact.lowercase(),
            MemorySensitivity.LOW.name, 1.0, "conversation", updatedAt, updatedAt, updatedAt, active
        )

    @Test fun retrievesOnlyRelevantResponseStyleMemory() {
        val memories = listOf(
            memory("1", "preference:favorite:game", "Zopy's favorite game is BGMI", 30),
            memory("2", "preference:response_style", "Zopy prefers short answers", 20),
            memory("3", "preference:likes:movies", "Zopy likes horror movies", 10)
        )
        val selected = MemoryRelevanceSelector.select("What response style do I prefer?", memories, 2)

        assertEquals(listOf("2"), selected.map { it.id })
    }

    @Test fun excludesInactiveAndBoundsResults() {
        val memories = listOf(
            memory("1", "project:lyra", "Zopy is building Lyra project", 20, active = false),
            memory("2", "project:myra", "Zopy is building Myra project", 10)
        )
        val selected = MemoryRelevanceSelector.select("Lyra codename", memories, 1)

        assertTrue(selected.isEmpty())
    }

    @Test fun emptyQueryReturnsRecentBoundedContext() {
        val memories = listOf(
            memory("1", "a", "first", 1),
            memory("2", "b", "second", 2),
            memory("3", "c", "third", 3)
        )
        assertEquals(listOf("3", "2"), MemoryRelevanceSelector.select("", memories, 2).map { it.id })
    }
}
