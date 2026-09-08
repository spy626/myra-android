package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySemanticInterpreterTest {
    @Test fun relationshipChangeRenameAndDeleteAreDifferentProperties() {
        assertEquals(
            MemorySemanticIntent.REPLACE_RELATIONSHIP,
            frame("Ab mera dost Kareem nahi hai, Naufal hai").intent
        )
        assertEquals(
            MemorySemanticIntent.RENAME_ENTITY,
            frame("Kareem ka naam actually Naufal hai").intent
        )
        assertEquals(
            MemorySemanticIntent.DELETE_ENTITY,
            frame("Kareem ko meri memory se hata do").intent
        )
    }

    @Test fun currentAdditiveAndHistoricalRelationshipsHaveDistinctFrames() {
        val additive = listOf(
            "Naufal mera dost hai",
            "Naufal bhi mera dost hai",
            "Jonathan is my friend",
            "I consider Rumaisa a close friend"
        ).map(::frame)
        assertTrue(additive.all { it.intent == MemorySemanticIntent.ADD_RELATIONSHIP })
        assertEquals(PersonRelationship.GOOD_FRIEND, additive.last().relationship)
        assertEquals(MemorySemanticIntent.REMOVE_RELATIONSHIP, frame("Jonathan is not my friend anymore").intent)
        val historical = frame("Pehle Ayesha mera dost tha")
        assertEquals(MemorySemanticIntent.ADD_LINKED_FACT, historical.intent)
        assertEquals(MemoryTemporalScope.HISTORICAL, historical.temporalScope)
    }

    @Test fun temporaryContactAndOrdinaryNegationDoNotInventRelationshipChanges() {
        assertEquals(MemorySemanticIntent.TRANSIENT_CONTEXT, frame("Aaj Naufal ke saath game khela").intent)
        assertEquals(MemorySemanticIntent.NONE, frame("Kareem se aaj baat nahi hui").intent)
        assertEquals(MemorySemanticIntent.NONE, frame("Kareem ne mujhe call nahi kiya").intent)
    }

    @Test fun temporaryEventWithExplicitRelationshipKeepsOnlyStatedDurableMeaning() {
        val result = frame("Aaj main Naufal ke saath game khel ke aaya hun, woh mera bohot accha dost hai")
        assertEquals(MemorySemanticIntent.ADD_RELATIONSHIP, result.intent)
        assertEquals("Naufal", result.person)
        assertEquals(PersonRelationship.GOOD_FRIEND, result.relationship)
    }

    @Test fun questionsAlwaysResolveReadOnly() {
        listOf(
            "Mera dost kaun hai?",
            "Mere dost kaun kaun hain?",
            "Kya Kareem mera dost hai?",
            "Kareem ab bhi mera dost hai kya?",
            "Kiska naam update nahi ho paya?"
        ).forEach { assertEquals(it, MemorySemanticIntent.RECALL, frame(it).intent) }
    }

    @Test fun contextualPronounUsesUniqueRecentPersonAndOtherwiseClarifies() {
        val known = entity("Naufal", "person-naufal")
        assertEquals("Naufal", MemorySemanticInterpreter.interpret("Woh gaming videos banata hai", listOf(known), "Naufal").person)
        val ambiguous = MemorySemanticInterpreter.interpret(
            "Woh gaming videos banata hai",
            listOf(known, entity("Kareem", "person-kareem")),
            null
        )
        assertEquals(MemorySemanticIntent.CLARIFY, ambiguous.intent)
    }

    @Test fun multipleFriendsCoexistAndRelationshipRemovalKeepsPerson() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        assertTrue(brain.processFinalTurn("Mera dost Kareem hai") is MemoryBrainOutcome.Mutated)
        assertTrue(brain.processFinalTurn("Naufal bhi mera dost hai") is MemoryBrainOutcome.Mutated)

        val before = repository.allActive()
        val kareemId = before.first { it.entityName == "Kareem" }.entityId
        val naufalId = before.first { it.entityName == "Naufal" }.entityId
        assertNotEquals(kareemId, naufalId)
        assertTrue(before.any { it.entityName == "Kareem" && it.fact.contains("friend") })
        assertTrue(before.any { it.entityName == "Naufal" && it.fact.contains("friend") })

        assertTrue(brain.processFinalTurn("Ab Kareem mera dost nahi hai") is MemoryBrainOutcome.Mutated)
        val after = repository.allActive()
        assertTrue(after.any { it.entityName == "Kareem" && it.stableKey.endsWith(":identity") })
        assertFalse(after.any { it.entityName == "Kareem" && it.fact.contains("Zopy's friend") })
        assertTrue(after.any { it.entityName == "Naufal" && it.fact.contains("friend") })
        MemoryWorkingContext.clear()
    }

    @Test fun relationshipReplacementDoesNotRenameOrDeleteOldEntity() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        brain.processFinalTurn("Mera dost Kareem hai")
        val oldId = repository.allActive().first { it.entityName == "Kareem" }.entityId

        val result = brain.processFinalTurn("Ab mera dost Kareem nahi hai, Naufal hai")

        assertTrue(result is MemoryBrainOutcome.Mutated)
        val active = repository.allActive()
        assertTrue(active.any { it.entityName == "Kareem" && it.entityId == oldId })
        assertFalse(active.any { it.entityName == "Kareem" && it.fact.contains("Zopy's friend") })
        assertTrue(active.any { it.entityName == "Naufal" && it.fact.contains("friend") })
        MemoryWorkingContext.clear()
    }

    @Test fun relationshipDowngradePreservesEntityAndAddsNormalFriend() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        repository.addPersonRelationship("Jonathan", PersonRelationship.BEST_FRIEND)
        val entityId = repository.allActive().first { it.entityName == "Jonathan" }.entityId
        val brain = MemoryBrainCoordinator(repository)

        val result = brain.processFinalTurn("Jonathan ab best friend nahi, bas normal dost hai")

        assertTrue(result is MemoryBrainOutcome.Mutated)
        val active = repository.allActive().filter { it.entityName == "Jonathan" }
        assertTrue(active.all { it.entityId == entityId })
        assertFalse(active.any { it.fact.contains("best friend") })
        assertTrue(active.any { it.fact.contains("Zopy's friend") })
        MemoryWorkingContext.clear()
    }

    @Test fun clearActualNameCorrectionPreservesEntityId() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        brain.processFinalTurn("Mera dost Kareem hai")
        brain.processFinalTurn("Woh gaming videos banata hai")
        val entityId = repository.allActive().first { it.entityName == "Kareem" }.entityId

        val result = brain.processFinalTurn("Kareem ka naam actually Naufal hai")

        assertTrue(result is MemoryBrainOutcome.Mutated)
        val active = repository.allActive().filter { it.entityName == "Naufal" }
        assertTrue(active.isNotEmpty())
        assertTrue(active.all { it.entityId == entityId })
        assertFalse(repository.allActive().any { it.entityName == "Kareem" })
        MemoryWorkingContext.clear()
    }

    private fun frame(text: String) = MemorySemanticInterpreter.interpret(text, emptyList(), null)

    private fun entity(name: String, entityId: String) = MemoryEntity(
        id = entityId,
        stableKey = "person:${name.lowercase()}:identity",
        category = MemoryCategory.PERSON.name,
        fact = "$name is a person known to Zopy",
        normalizedFact = name.lowercase(),
        sensitivity = MemorySensitivity.PERSONAL.name,
        confidence = .95,
        source = "test",
        createdAt = 1,
        updatedAt = 1,
        lastConfirmedAt = 1,
        entityId = entityId,
        entityName = name
    )
}
