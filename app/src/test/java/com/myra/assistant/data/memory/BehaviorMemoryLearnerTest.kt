package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BehaviorMemoryLearnerTest {
    @Test fun oneObservationNeverBecomesDurablePattern() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val learner = BehaviorMemoryLearner(repository)
        assertNull(learner.observe(BehaviorSignal(BehaviorObservationKind.APP_USAGE, "YouTube", "s1", 0)))
        assertTrue(repository.allActive().isEmpty())
    }

    @Test fun repeatedAppUsagePromotesOnlyAfterCrossSessionCrossDayEvidence() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao()); val learner = BehaviorMemoryLearner(repository)
        repeat(6) { index -> learner.observe(BehaviorSignal(BehaviorObservationKind.APP_USAGE,
            "YouTube", "s$index", index * BehaviorMemoryLearner.DAY_MS)) }
        val row = repository.allActive().single()
        assertEquals(MemoryCategory.APP_USAGE.name, row.category)
        assertEquals(MemoryProvenance.BEHAVIOR_PATTERN.name, row.provenance)
        assertEquals("Uses YouTube frequently", row.fact)
    }

    @Test fun repeatedCrossSessionCrossDayEvidencePromotesConservativePattern() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao()); val learner = BehaviorMemoryLearner(repository)
        repeat(6) { index -> learner.observe(BehaviorSignal(BehaviorObservationKind.YOUTUBE_CHANNEL,
            "Jonathan Gaming", "s${index % 3}", index * BehaviorMemoryLearner.DAY_MS)) }
        val row = repository.allActive().single()
        assertEquals(MemoryCategory.CONTENT_INTEREST.name, row.category)
        assertEquals("Frequently watches Jonathan Gaming", row.fact)
        assertFalse(row.fact.contains("favorite", true))
    }

    @Test fun repeatedTopicsBecomeCurrentInterestAndDecay() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao()); val learner = BehaviorMemoryLearner(repository)
        repeat(6) { index -> learner.observe(BehaviorSignal(BehaviorObservationKind.CONTENT_TOPIC,
            "AI", "s$index", index * BehaviorMemoryLearner.DAY_MS)) }
        assertEquals(MemoryCategory.CURRENT_INTEREST.name, repository.allActive().single().category)
        learner.decay(60 * BehaviorMemoryLearner.DAY_MS)
        assertTrue(repository.allActive().isEmpty())
    }
}
