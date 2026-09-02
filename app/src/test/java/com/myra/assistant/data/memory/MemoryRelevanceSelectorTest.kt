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

    @Test fun stalePersonAliasInInternalKeyCannotResurrectOldName() {
        val memories = listOf(
            memory("1", "person:now_farah:gaming_channel", "Naufal has a gaming channel", 20),
            memory("2", "person:best_friend:naufal", "Zopy's best friend is Naufal", 10)
        )

        assertTrue(MemoryRelevanceSelector.select("Now Farah", memories, 10).isEmpty())
        assertEquals(2, MemoryRelevanceSelector.select("Naufal", memories, 10).size)
    }

    @Test fun contextualReferenceRetrievesPriorWorkflowAndUsualApp() {
        val memories = listOf(
            memory("1", "workflow:app releases", "Zopy usually tests on Android phone for app releases", 10)
                .copy(category = MemoryCategory.WORKFLOW.name),
            memory("2", "app_usage:reading articles", "Zopy usually uses Chrome for reading articles", 20)
                .copy(category = MemoryCategory.APP_USAGE.name),
            memory("3", "preference:likes:horror", "Zopy likes horror", 30)
        )

        val selected = MemoryRelevanceSelector.select("Do it like before", memories, 3)

        assertEquals(listOf("2", "1", "3"), selected.map { it.id })
    }

    @Test fun frequentlyUsedRelevantMemoryHasHigherPriority() {
        val newer = memory("1", "workflow:test", "Zopy uses phone testing", 20)
            .copy(category = MemoryCategory.WORKFLOW.name)
        val frequent = memory("2", "workflow:test", "Zopy uses device testing", 10)
            .copy(category = MemoryCategory.WORKFLOW.name, useCount = 4, lastUsedAt = 30)

        assertEquals(
            "2",
            MemoryRelevanceSelector.select("usual workflow test", listOf(newer, frequent), 1).single().id
        )
    }
}
