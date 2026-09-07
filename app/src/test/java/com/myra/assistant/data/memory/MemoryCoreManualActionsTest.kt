package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCoreManualActionsTest {
    @Test fun manualLinkedAddUsesManualSeedProvenance() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())

        val result = MemoryCoreManualActions.add(
            repository,
            "Mera best friend Kareem hai",
            MemoryCategory.PERSON
        )

        assertTrue(result is MemoryWriteResult.Saved)
        val row = repository.allActive().single()
        assertEquals(MemoryProvenance.MANUAL_UI_SEED.name, row.provenance)
        assertEquals("Kareem", row.entityName)
    }

    @Test fun editingLinkedLifeEventPreservesPersonIdentity() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val entityId = NaturalMemoryExtractor.stablePersonId("Kareem")
        repository.saveGrounded(MemoryCandidate(
            MemoryCategory.PERSON,
            "Kareem is Zopy's friend",
            "person:kareem:identity",
            MemorySensitivity.PERSONAL,
            .97,
            provenance = MemoryProvenance.USER_DIRECT_STATEMENT,
            entityId = entityId,
            entityName = "Kareem"
        ))
        repository.saveGrounded(MemoryCandidate(
            MemoryCategory.LIFE_EVENT,
            "Zopy travelled to Manali with Kareem",
            "person:kareem:life_event:manali",
            MemorySensitivity.PERSONAL,
            .94,
            provenance = MemoryProvenance.USER_DIRECT_STATEMENT,
            entityId = entityId,
            entityName = "Kareem"
        ))
        val event = repository.allActive().first { it.category == MemoryCategory.LIFE_EVENT.name }

        val result = MemoryCoreManualActions.edit(
            repository,
            event,
            "Zopy travelled to Manali and Kerala with Kareem",
            MemoryCategory.LIFE_EVENT
        )

        assertTrue(result is MemoryWriteResult.Saved)
        val active = repository.allActive()
        assertEquals(2, active.size)
        assertTrue(active.any { it.category == MemoryCategory.PERSON.name && it.entityId == entityId })
        val edited = active.single { it.category == MemoryCategory.LIFE_EVENT.name }
        assertEquals(entityId, edited.entityId)
        assertEquals("Kareem", edited.entityName)
        assertEquals(MemoryProvenance.MANUAL_UI_EDIT.name, edited.provenance)
        assertEquals("Zopy travelled to Manali and Kerala with Kareem", edited.fact)
    }

    @Test fun prohibitedManualSeedIsRejected() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val result = MemoryCoreManualActions.add(
            repository,
            "My OTP is 123456",
            MemoryCategory.IDENTITY
        )
        assertTrue(result is MemoryWriteResult.Rejected)
        assertTrue(repository.allActive().isEmpty())
    }
}
