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
        // Reproduce the video: ASR froze "Now Farah". The correction parser
        // resolves spoken "Nowar" back to this persisted identity.
        repository.saveAdditionalBestFriend(bestFriend("Now Farah"))
        repository.save(gamingChannel("Now Farah"))

        assertTrue(repository.renameBestFriend("Now Farah", "Naufal"))

        val active = dao.recent(50)
        val newLookup = repository.relevant("Naufal", 10)
        val oldLookup = repository.relevant("Now Farah", 10)
        assertEquals(
            setOf("Zopy's best friend is Naufal", "Naufal has a gaming channel"),
            active.map { it.fact }.toSet()
        )
        assertTrue(active.none {
            it.fact.contains("Now Farah", true) || it.stableKey.contains("now_farah", true)
        })
        assertEquals(2, newLookup.size)
        assertTrue(oldLookup.isEmpty())
    }

    @Test fun deletingPersonDeletesRelationshipAndLinkedFact() = runBlocking {
        val dao = FakeMemoryDao()
        val repository = MemoryRepository(dao)
        repository.saveAdditionalBestFriend(bestFriend("Naufal"))
        repository.save(gamingChannel("Naufal"))

        assertTrue(repository.forgetMatching("Naufal"))

        assertTrue(dao.recent(50).isEmpty())
        assertFalse(dao.all().filter { it.active }.any { it.stableKey.startsWith("person:naufal:") })
    }

    @Test fun deletingKareemAndLinkedFactRemainsAbsentAfterReconnect() = runBlocking {
        val dao = FakeMemoryDao()
        MemoryRepository(dao).apply {
            saveAdditionalBestFriend(bestFriend("Kareem"))
            save(gamingChannel("Kareem"))
            assertTrue(forgetMatching("Kareem"))
        }
        repeat(2) {
            val reopened = MemoryRepository(dao)
            assertTrue(reopened.relevant("Kareem", 10).isEmpty())
            assertTrue(dao.recent(50).isEmpty())
        }
    }

    @Test fun karimaCorrectionConsolidatesExistingKareemAndLinkedFact() = runBlocking {
        val dao = FakeMemoryDao()
        val repository = MemoryRepository(dao)
        repository.saveAdditionalBestFriend(bestFriend("Karima"))
        repository.save(gamingChannel("Karima"))
        repository.saveAdditionalBestFriend(bestFriend("Kareem"))

        assertTrue(repository.renameBestFriend("Karima", "Kareem"))

        val active = dao.recent(50)
        assertEquals(1, active.count { MemoryRelationshipPolicy.isBestFriend(it) })
        assertEquals(
            setOf("Zopy's best friend is Kareem", "Kareem has a gaming channel"),
            active.map { it.fact }.toSet()
        )
        assertTrue(active.none {
            it.fact.contains("Karima", true) || it.stableKey.contains("karima", true)
        })
    }

    @Test fun karimaCorrectionPersistsAcrossTwoRepositorySessions() = runBlocking {
        val dao = FakeMemoryDao()
        MemoryRepository(dao).apply {
            saveAdditionalBestFriend(bestFriend("Karima"))
            save(gamingChannel("Karima"))
            assertTrue(renameBestFriend("Karima", "Kareem"))
        }

        repeat(2) {
            val reopened = MemoryRepository(dao)
            assertEquals(
                setOf("Zopy's best friend is Kareem", "Kareem has a gaming channel"),
                reopened.relevant("Kareem", 10).map { row -> row.fact }.toSet()
            )
            assertTrue(reopened.relevant("Karima", 10).isEmpty())
        }
    }

    @Test fun failedRenameCannotPassReadAfterWriteVerification() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        assertFalse(repository.renameBestFriend("Karima", "Kareem"))
    }

    @Test fun recordedNamedKarimAliasIsMergedAndDeleteRemovesWholeIdentity() = runBlocking {
        val dao = FakeMemoryDao()
        val repository = MemoryRepository(dao)
        repository.saveAdditionalBestFriend(bestFriend("Karima"))
        repository.save(gamingChannel("Karima"))
        repository.saveAdditionalBestFriend(bestFriend("Named Karim"))

        assertTrue(repository.renameBestFriend("Karima", "Kareem"))
        assertEquals(
            setOf("Zopy's best friend is Kareem", "Kareem has a gaming channel"),
            dao.recent(50).map { it.fact }.toSet()
        )

        assertTrue(repository.forgetMatching("Kareem"))
        assertTrue(dao.recent(50).isEmpty())
    }

    @Test fun settingsDeleteOfBestFriendAlsoDeletesLinkedFacts() = runBlocking {
        val dao = FakeMemoryDao()
        val repository = MemoryRepository(dao)
        repository.saveAdditionalBestFriend(bestFriend("Kareem"))
        repository.save(gamingChannel("Kareem"))
        val relationship = repository.allActive().first(MemoryRelationshipPolicy::isBestFriend)

        assertTrue(repository.forgetFromSettings(relationship))
        assertTrue(repository.allActive().isEmpty())
    }

    @Test fun manualSettingsFactsUseSafetyPolicyAndCanBeEdited() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val rejected = repository.saveManualFact("My OTP is 123456", MemoryCategory.IDENTITY)
        assertTrue(rejected is MemoryWriteResult.Rejected)

        val saved = repository.saveManualFact("Zopy likes astronomy", MemoryCategory.PREFERENCE)
        assertTrue(saved is MemoryWriteResult.Saved)
        val row = repository.allActive().single()
        assertEquals(ManualMemoryPolicy.SOURCE, row.source)

        val updated = repository.updateManualFact(
            row.id,
            "Zopy likes astronomy and space documentaries",
            MemoryCategory.PREFERENCE
        )
        assertTrue(updated is MemoryWriteResult.Saved)
        assertEquals(
            "Zopy likes astronomy and space documentaries",
            repository.allActive().single().fact
        )
    }

    @Test fun manualBestFriendAddUsesAdditionalIdentityWithoutReplacingExistingFriend() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        repository.saveAdditionalBestFriend(bestFriend("Ayesha"))

        val result = repository.saveManualFact(
            "Mera best friend Kareem hai",
            MemoryCategory.PERSON
        )

        assertTrue(result is MemoryWriteResult.Saved)
        assertEquals(
            setOf("Ayesha", "Kareem"),
            repository.allActive()
                .filter(MemoryRelationshipPolicy::isBestFriend)
                .mapNotNull { MemoryRelationshipPolicy.personName(it.fact) }
                .toSet()
        )
    }
    @Test fun stablePreferenceKeyUpdatesInsteadOfDuplicating() = runBlocking {
        val dao = FakeMemoryDao()
        val repository = MemoryRepository(dao)
        val short = (AutomaticMemoryChangeParser.parse("Give me short answers") as AutomaticMemoryChange.Save).candidate
        val detailed = (AutomaticMemoryChangeParser.parse(
            "Actually, give me detailed answers"
        ) as AutomaticMemoryChange.Save).candidate

        repository.save(short)
        // Reproduce data written by the temporary Phase 3A alias as well.
        dao.upsert(dao.recent(10).single().copy(
            id = "legacy-style",
            stableKey = "communication:response_style",
            fact = "Zopy prefers short answers"
        ))
        repository.save(detailed)

        val active = repository.allActive().filter { it.stableKey.endsWith("response_style") }
        assertEquals(1, active.size)
        assertEquals("preference:response_style", active.single().stableKey)
        assertEquals("Zopy prefers detailed answers", active.single().fact)
    }

    @Test fun crossCategoryPreferenceIsReplacedAndUnrelatedPreferenceSurvives() = runBlocking {
        val dao = FakeMemoryDao()
        val repository = MemoryRepository(dao)
        repository.save(MemoryCandidate(
            MemoryCategory.COMMUNICATION_STYLE, "Zopy prefers short answers",
            "communication:response_style", MemorySensitivity.LOW, .95
        ))
        repository.save(MemoryCandidate(
            MemoryCategory.PREFERENCE, "Zopy likes horror movies",
            "preference:likes:horror movies", MemorySensitivity.LOW, .93
        ))
        val detailed = MemoryCandidate(
            MemoryCategory.PREFERENCE, "Zopy prefers detailed explanations",
            "semantic:preference:answer_length", MemorySensitivity.LOW, .94
        )

        repository.save(detailed)
        repository.save(detailed.copy(fact = "Zopy prefers long detailed responses"))

        val active = repository.allActive()
        val styles = active.filter(PreferenceMemoryIdentity::isResponseVerbosity)
        assertEquals(1, styles.size)
        assertEquals("preference:response_style", styles.single().stableKey)
        assertEquals("Zopy prefers long detailed responses", styles.single().fact)
        assertTrue(active.any { it.fact == "Zopy likes horror movies" })
    }

    @Test fun reconnectReconciliationRepairsAlreadyStoredConflictingRows() = runBlocking {
        val dao = FakeMemoryDao()
        val repository = MemoryRepository(dao)
        val now = System.currentTimeMillis()
        dao.upsert(MemoryEntity(
            "old-style", "communication:response_style", MemoryCategory.COMMUNICATION_STYLE.name,
            "Zopy prefers short answers", "zopy prefers short answers", MemorySensitivity.LOW.name,
            .95, "automatic_conversation", now - 100, now - 100, now - 100
        ))
        dao.upsert(MemoryEntity(
            "new-style", "semantic:preference:answer_length", MemoryCategory.PREFERENCE.name,
            "Zopy prefers detailed answers", "zopy prefers detailed answers", MemorySensitivity.LOW.name,
            .94, "gemini_grounded_conversation", now, now, now
        ))

        repository.reconcilePreferenceDimensions()

        val active = repository.allActive()
        assertEquals(1, active.size)
        assertEquals("preference:response_style", active.single().stableKey)
        assertEquals(MemoryCategory.PREFERENCE.name, active.single().category)
        assertEquals("Zopy prefers detailed answers", active.single().fact)
    }

    @Test fun relevantRetrievalRecordsUsageWithoutDuplicatingMemory() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        repository.save(AutomaticMemoryExtractor.extract("I usually use Chrome for reading articles")!!)

        repository.relevant("Which app do I usually use for reading articles?", 1)
        repository.relevant("Use my usual app for reading articles", 1)

        val memory = repository.allActive().single()
        assertEquals(2, memory.useCount)
        assertTrue(memory.lastUsedAt > 0)
    }

    private fun bestFriend(name: String) = MemoryCandidate(
        MemoryCategory.PERSON, "Zopy's best friend is $name",
        MemoryRelationshipPolicy.BEST_FRIEND_KEY, MemorySensitivity.PERSONAL, .96, source = "test"
    )

    private fun gamingChannel(name: String) = MemoryCandidate(
        MemoryCategory.PERSON, "$name has a gaming channel",
        "person:${name.lowercase()}:gaming_channel", MemorySensitivity.PERSONAL, .94, source = "test"
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

    override suspend fun markUsed(id: String, usedAt: Long): Int {
        val row = rows[id]?.takeIf { it.active } ?: return 0
        rows[id] = row.copy(useCount = row.useCount + 1, lastUsedAt = usedAt)
        return 1
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
