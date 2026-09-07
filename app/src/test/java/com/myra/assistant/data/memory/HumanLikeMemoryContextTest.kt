package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanLikeMemoryContextTest {
    @Test fun colloquialCodingPreferenceSavesWithoutRememberCommand() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)

        val outcome = brain.processFinalTurn("Mujhe na codes karne accha lagta hai")
        val mutated = outcome as MemoryBrainOutcome.Mutated

        assertTrue(mutated.result is MemoryWriteResult.Saved)
        assertTrue(!mutated.explicit)
        val active = repository.allActive()
        assertEquals(1, active.size)
        assertEquals("Zopy likes coding", active.single().fact)
        assertEquals("preference:likes:coding", active.single().stableKey)
        assertTrue(repository.relevant("coding", 5).any { it.fact == "Zopy likes coding" })
        MemoryWorkingContext.clear()
    }

    @Test fun differentNaturalWordingReusesSameMemoryInsteadOfDuplicatingIt() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)

        brain.processFinalTurn("Mujhe na codes karne accha lagta hai")
        brain.processFinalTurn("Coding mein maza aata hai mujhe")

        val active = repository.allActive().filter { it.stableKey == "preference:likes:coding" }
        assertEquals(1, active.size)
        assertEquals("Zopy likes coding", active.single().fact)
        MemoryWorkingContext.clear()
    }

    @Test fun implicitPriorTopicQueryCanRetrieveTheSameSemanticMemory() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        brain.processFinalTurn("Mujhe coding accha lagta hai")

        val recalled = repository.relevant("wahi jo kal coding ke baare mein bola tha", 5)

        assertTrue(recalled.any { it.stableKey == "preference:likes:coding" })
    }

    @Test fun explicitDislikeSupersedesOldPreferenceWithoutForgettingContext() = runBlocking {
        MemoryWorkingContext.clear()
        val dao = FakeMemoryDao()
        val repository = MemoryRepository(dao)
        val brain = MemoryBrainCoordinator(repository)

        brain.processFinalTurn("I like web development")
        brain.processFinalTurn("Web development mujhe utna pasand nahi hai")

        val active = repository.allActive().filter { it.stableKey == "preference:likes:web development" }
        assertEquals(1, active.size)
        assertEquals("Zopy does not like Web development", active.single().fact)
        assertTrue(dao.all().any {
            it.lifecycleStatus == MemoryLifecycleStatus.SUPERSEDED.name &&
                it.fact.equals("Zopy likes web development", ignoreCase = true)
        })
        MemoryWorkingContext.clear()
    }

    @Test fun namedPersonPreferenceLinksToExistingPersonNotToZopy() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)

        brain.processFinalTurn("Mera friend Kareem hai")
        brain.processFinalTurn("Kareem ko coding bhi pasand hai")

        val active = repository.allActive()
        val person = active.single { it.stableKey == "person:kareem:identity" }
        val preference = active.single { it.stableKey == "person:kareem:preference:coding" }
        assertEquals(person.entityId, preference.entityId)
        assertEquals("Kareem", preference.entityName)
        assertEquals("Kareem likes coding", preference.fact)
        assertTrue(active.none { it.fact == "Zopy likes coding" })
        MemoryWorkingContext.clear()
    }

    @Test fun uncertainInferenceAboutPersonIsIgnoredInsteadOfBecomingMemory() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        brain.processFinalTurn("Mera friend Kareem hai")
        val before = repository.allActive().size

        val outcome = brain.processFinalTurn("Mereko lagta hai Kareem ko coding pasand hogi")

        assertTrue(outcome is MemoryBrainOutcome.Ignored)
        assertEquals(before, repository.allActive().size)
        assertTrue(repository.allActive().none { it.fact.contains("coding", ignoreCase = true) })
        MemoryWorkingContext.clear()
    }
}
