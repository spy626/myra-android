package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalMemoryRequestResolverTest {
    @Test fun naturalForgetMorphologyIsUnderstoodWithoutCommandPhrases() {
        assertTrue(NaturalMemoryRequestResolver.hasForgetIntent("Kareem ka sab bhool jao"))
        assertTrue(NaturalMemoryRequestResolver.hasForgetIntent("Kareem ka sab bhule jaao"))
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
}
