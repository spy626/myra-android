package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalMemoryRequestResolverTest {
    @Test fun naturalForgetMorphologyIsUnderstoodWithoutCommandPhrases() {
        assertTrue(NaturalMemoryRequestResolver.hasForgetIntent("Kareem ka sab bhool jao"))
        assertTrue(NaturalMemoryRequestResolver.hasForgetIntent("Kareem ka sab bhule jaao"))
        assertTrue(NaturalMemoryRequestResolver.hasForgetIntent("Kareem ka sub bhule jaao"))
        assertTrue(NaturalMemoryRequestResolver.hasForgetIntent("Kareem ko bhul dena"))
        assertTrue(NaturalMemoryRequestResolver.hasForgetIntent("Kareem ko hatao"))
        assertFalse(NaturalMemoryRequestResolver.hasForgetIntent("Kareem ko mat bhoolna"))
        assertFalse(NaturalMemoryRequestResolver.hasForgetIntent("Kareem ko nahi bhulna"))
    }

    @Test fun spokenBhuleJaaoRemovesWholeLinkedPersonIdentity() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        NaturalMemoryExtractor.extract("Mera friend Kareem hai").forEach { repository.saveGrounded(it) }
        NaturalMemoryExtractor.extract("Main Kareem ke saath Manali aur Kerala travel gaya tha")
            .forEach { repository.saveGrounded(it) }

        val outcome = brain.processFinalTurn("Kareem ka sab bhule jaao")

        assertTrue(outcome is MemoryBrainOutcome.Deleted)
        assertTrue((outcome as MemoryBrainOutcome.Deleted).succeeded)
        assertTrue(repository.allActive().none { it.entityName.equals("Kareem", ignoreCase = true) })
        assertTrue(repository.relevant("Kareem", 10).isEmpty())
        MemoryWorkingContext.clear()
    }

    @Test fun wholePersonForgetAlsoClearsCrossCategoryAndPassiveEvidence() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        NaturalMemoryExtractor.extract("Mera friend Kareem hai").forEach { repository.saveGrounded(it) }
        NaturalMemoryExtractor.extract("Main Kareem ke saath Manali aur Kerala travel gaya tha")
            .forEach { repository.saveGrounded(it) }
        val behaviorKey = "behavior:youtube_channel:kareem"
        repository.saveGrounded(MemoryCandidate(
            MemoryCategory.CONTENT_INTEREST,
            "Frequently watches Kareem",
            behaviorKey,
            MemorySensitivity.LOW,
            .86,
            provenance = MemoryProvenance.BEHAVIOR_PATTERN
        ))
        repository.recordBehavior(BehaviorObservationEntity(
            id = "behavior-kareem",
            stableKey = behaviorKey,
            kind = BehaviorObservationKind.YOUTUBE_CHANNEL.name,
            label = "Kareem",
            observationCount = 8,
            sessionCount = 4,
            dayCount = 3,
            firstObservedAt = 1L,
            lastObservedAt = 2L,
            lastSessionId = "s4",
            lastDayBucket = 2L
        ))
        MemoryWorkingContext.person("Kareem")
        assertTrue(repository.relevant("Kareem", 10).isNotEmpty())

        val outcome = brain.processFinalTurn("Kareem ka sub bhule jaao")

        assertTrue(outcome is MemoryBrainOutcome.Deleted)
        assertTrue((outcome as MemoryBrainOutcome.Deleted).succeeded)
        assertTrue(repository.allActive().none { PersonLinkedMemoryIdentity.mentionsName(it.fact, "Kareem") })
        assertNull(repository.behavior(behaviorKey))
        assertTrue(MemorySessionIndex.snapshot().isEmpty())
        assertNull(MemoryWorkingContext.recentPerson)
        MemoryWorkingContext.clear()
    }
}
