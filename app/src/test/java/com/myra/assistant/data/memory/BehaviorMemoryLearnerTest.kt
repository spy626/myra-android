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

    @Test fun repeatedTopicsBecomeCurrentInterestAndFollowLifecycleDecay() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao()); val learner = BehaviorMemoryLearner(repository)
        repeat(6) { index -> learner.observe(BehaviorSignal(BehaviorObservationKind.CONTENT_TOPIC,
            "AI", "s$index", index * BehaviorMemoryLearner.DAY_MS)) }
        assertEquals(MemoryCategory.CURRENT_INTEREST.name, repository.allActive().single().category)

        // Last observation above is day 5. At day 40 the pattern is 35 days old,
        // so it should remain active but move into WEAKENING.
        learner.decay(40 * BehaviorMemoryLearner.DAY_MS)
        assertEquals(MemoryLifecycleStatus.WEAKENING.name, repository.allActive().single().lifecycleStatus)

        // At day 55 it is 50 days old and should remain stored as HISTORICAL.
        learner.decay(55 * BehaviorMemoryLearner.DAY_MS)
        assertEquals(MemoryLifecycleStatus.HISTORICAL.name, repository.allActive().single().lifecycleStatus)

        // At day 66 it is 61 days old and crosses the 60-day inactive threshold.
        learner.decay(66 * BehaviorMemoryLearner.DAY_MS)
        assertTrue(repository.allActive().isEmpty())
    }

    @Test fun contentTopicExtractorDiscoversNonHardcodedTopics() {
        val topics = ContentTopicExtractor.extract(listOf(
            "Kubernetes deployment tutorial for beginners",
            "Kubernetes operators explained",
            "Building reliable Kubernetes clusters"
        ))
        assertTrue(topics.any { it.equals("Kubernetes", ignoreCase = true) })
    }

    @Test fun mostUsedAppRequiresClearComparativeLead() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val learner = BehaviorMemoryLearner(repository)

        // YouTube has strong multi-day/session evidence.
        repeat(10) { index ->
            learner.observe(BehaviorSignal(
                BehaviorObservationKind.APP_USAGE,
                "YouTube",
                "yt-$index",
                index * BehaviorMemoryLearner.DAY_MS
            ))
        }

        // Chrome is used too, but with materially less evidence.
        repeat(5) { index ->
            learner.observe(BehaviorSignal(
                BehaviorObservationKind.APP_USAGE,
                "Chrome",
                "ch-$index",
                (index + 10L) * BehaviorMemoryLearner.DAY_MS
            ))
        }

        val mostUsed = repository.allActive().single { it.stableKey == BehaviorMemoryLearner.MOST_USED_APP_KEY }
        assertEquals("Usually uses YouTube the most", mostUsed.fact)
        assertEquals(MemoryCategory.APP_USAGE.name, mostUsed.category)
        assertEquals(MemoryProvenance.BEHAVIOR_PATTERN.name, mostUsed.provenance)
    }

    @Test fun staleMostUsedSummaryIsRetiredWhenAnotherAppCatchesUp() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val learner = BehaviorMemoryLearner(repository)

        repeat(10) { index ->
            learner.observe(BehaviorSignal(
                BehaviorObservationKind.APP_USAGE,
                "YouTube",
                "yt-$index",
                index * BehaviorMemoryLearner.DAY_MS
            ))
        }
        assertTrue(repository.allActive().any {
            it.stableKey == BehaviorMemoryLearner.MOST_USED_APP_KEY && it.fact.contains("YouTube")
        })

        repeat(10) { index ->
            learner.observe(BehaviorSignal(
                BehaviorObservationKind.APP_USAGE,
                "Chrome",
                "ch-$index",
                (index + 10L) * BehaviorMemoryLearner.DAY_MS
            ))
        }
        assertFalse(repository.allActive().any { it.stableKey == BehaviorMemoryLearner.MOST_USED_APP_KEY })
    }
}
