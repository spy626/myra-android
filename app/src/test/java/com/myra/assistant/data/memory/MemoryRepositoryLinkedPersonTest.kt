package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryLinkedPersonTest {
    @Test fun linkedFactMovesToNewNameAndOldNameReturnsNothing() = runBlocking {
        val dao = FakeMemoryDao()
        val repository = MemoryRepository(dao)
        repository.saveAdditionalBestFriend(bestFriend("Nauphara"))
        repository.save(gamingChannel("Nauphara"))

        assertTrue(repository.renameBestFriend("Nauphara", "Naufal"))

        val active = dao.recent(50)
        val newLookup = repository.relevant("Naufal", 10)
        val oldLookup = repository.relevant("Nauphara", 10)
        assertEquals(
            setOf("Zopy's best friend is Naufal", "Naufal has a gaming channel"),
            active.map { it.fact }.toSet()
        )
        assertTrue(active.none {
            it.fact.contains("Nauphara", true) || it.stableKey.contains("nauphara", true)
        })
        assertEquals(2, newLookup.size)
        assertTrue(oldLookup.isEmpty())
    }

    @Test fun deletingPersonDeletesRelationshipAndLinkedFact() = runBlocking {
        val dao = FakeMemoryDao()
        val repository = MemoryRepository(dao)
        repository.saveAdditionalBestFriend(bestFriend("Naufal"))
        repository.save(gamingChannel("Naufal"))

        assertTrue(repository.forgetMatching("Naukra"))

        assertTrue(dao.recent(50).isEmpty())
        assertFalse(dao.all().filter { it.active }.any { it.stableKey.startsWith("person:naufal:") })
    }

    private fun bestFriend(name: String) = MemoryCandidate(
        MemoryCategory.PERSON, "Zopy's best friend is $name",
        MemoryRelationshipPolicy.BEST_FRIEND_KEY, MemorySensitivity.PERSONAL, .96, "test"
    )

    private fun gamingChannel(name: String) = MemoryCandidate(
        MemoryCategory.PERSON, "$name has a gaming channel",
        "person:${name.lowercase()}:gaming_channel", MemorySensitivity.PERSONAL, .94, "test"
    )
}

private class FakeMemoryDao : MemoryDao {
    private val rows = linkedMapOf<String, MemoryEntity>()
    fun all() = rows.values.toList()

    override suspend fun upsert(memory: MemoryEntity) {
        rows.values.firstOrNull { it.stableKey == memory.stableKey && it.id != memory.id }
            ?.let { rows.remove(it.id) }
        rows[memory.id] = memory
    }

    override suspend fun findByStableKey(stableKey: String) =
        rows.values.firstOrNull { it.stableKey == stableKey }

    override suspend fun recent(limit: Int) = rows.values.filter { it.active }
        .sortedByDescending { it.updatedAt }.take(limit)

    override suspend fun search(query: String, limit: Int) = recent(Int.MAX_VALUE)
        .filter { it.normalizedFact.contains(query) }.take(limit)

    override suspend fun deactivate(id: String, updatedAt: Long): Int {
        val row = rows[id]?.takeIf { it.active } ?: return 0
        rows[id] = row.copy(active = false, updatedAt = updatedAt)
        return 1
    }

    override suspend fun deactivateByStableKey(stableKey: String, updatedAt: Long): Int {
        val row = rows.values.firstOrNull { it.stableKey == stableKey && it.active } ?: return 0
        return deactivate(row.id, updatedAt)
    }

    override suspend fun rename(
        id: String,
        stableKey: String,
        fact: String,
        normalizedFact: String,
        updatedAt: Long
    ): Int {
        val row = rows[id]?.takeIf { it.active } ?: return 0
        rows[id] = row.copy(
            stableKey = stableKey,
            fact = fact,
            normalizedFact = normalizedFact,
            updatedAt = updatedAt,
            lastConfirmedAt = updatedAt
        )
        return 1
    }

    override suspend fun deleteAll() = rows.clear()
}
