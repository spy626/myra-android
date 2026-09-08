package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemorySemanticInterpreterTest {
    @Test fun modelStructuredOperationsRemainDistinctAndCoordinatorOwned() = runBlocking {
        val brain = MemoryBrainCoordinator(MemoryRepository(FakeMemoryDao()))
        val meanings = listOf(
            frame(MemorySemanticIntent.REPLACE_RELATIONSHIP, "Ari", "relationship changed", PersonRelationship.FRIEND, replacement = "Bea"),
            frame(MemorySemanticIntent.RENAME_ENTITY, "Ari", "name corrected", replacement = "Bea"),
            frame(MemorySemanticIntent.DELETE_ENTITY, "Ari", "forget Ari")
        )
        assertEquals(listOf(MemorySemanticIntent.REPLACE_RELATIONSHIP, MemorySemanticIntent.RENAME_ENTITY, MemorySemanticIntent.DELETE_ENTITY), meanings.map { it.intent })
        assertTrue(brain.prepareFinalTurn("relationship changed", listOf(meanings[0])).operations.single().intent == MemorySemanticIntent.REPLACE_RELATIONSHIP)
    }

    @Test fun compoundTurnPersistsRelationshipButKeepsTemporaryEventTransient() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        repository.addPersonRelationship("Ari", PersonRelationship.FRIEND)
        val brain = MemoryBrainCoordinator(repository)
        val text = "played today and Bea is a good friend"
        val plan = brain.prepareFinalTurn(text, listOf(
            frame(MemorySemanticIntent.TRANSIENT_CONTEXT, "Bea", "played today", fact = "played today", temporal = MemoryTemporalScope.TEMPORARY),
            frame(MemorySemanticIntent.ADD_RELATIONSHIP, "Bea", "Bea good friend", PersonRelationship.GOOD_FRIEND)
        ))
        assertEquals(2, plan.operations.size)
        assertTrue(brain.executeFinalTurnPlan(plan) is MemoryBrainOutcome.Mutated)
        val active = repository.allActive()
        assertTrue(active.any { it.entityName == "Ari" && it.fact.contains("friend") })
        assertTrue(active.any { it.entityName == "Bea" && it.fact.contains("good friend") })
        assertFalse(active.any { it.fact.contains("played today") })
    }

    @Test fun friendFamilyRemovalEndsSubtypesButKeepsPersonIdentity() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        repository.addPersonRelationship("Ari", PersonRelationship.GOOD_FRIEND)
        val brain = MemoryBrainCoordinator(repository)
        val plan = brain.prepareFinalTurn("Ari friendship ended", listOf(
            frame(MemorySemanticIntent.REMOVE_RELATIONSHIP, "Ari", "Ari friendship ended", PersonRelationship.FRIEND)
        ))
        brain.executeFinalTurnPlan(plan)
        assertTrue(repository.allActive().any { it.entityName == "Ari" && it.stableKey.endsWith(":identity") })
        assertFalse(repository.allActive().any { it.entityName == "Ari" && it.stableKey.contains(":relationship:") })
    }

    @Test fun relationshipReplacementUsesSameFriendHierarchyAndDoesNotRename() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        repository.addPersonRelationship("Ari", PersonRelationship.GOOD_FRIEND)
        val oldId = repository.allActive().first().entityId
        val brain = MemoryBrainCoordinator(repository)
        val plan = brain.prepareFinalTurn("Ari friendship replaced by Bea", listOf(
            frame(MemorySemanticIntent.REPLACE_RELATIONSHIP, "Ari", "Ari friendship replaced by Bea", PersonRelationship.FRIEND, replacement = "Bea")
        ))
        brain.executeFinalTurnPlan(plan)
        val active = repository.allActive()
        assertTrue(active.any { it.entityName == "Ari" && it.entityId == oldId })
        assertFalse(active.any { it.entityName == "Ari" && it.stableKey.contains(":relationship:") })
        assertTrue(active.any { it.entityName == "Bea" && it.fact.contains("friend") })
    }

    @Test fun downgradeEndsOnlyBestFriendAndKeepsNormalFriend() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        repository.addPersonRelationship("Ari", PersonRelationship.BEST_FRIEND)
        val id = repository.allActive().first().entityId
        val brain = MemoryBrainCoordinator(repository)
        val plan = brain.prepareFinalTurn("Ari is now a normal friend", listOf(
            frame(MemorySemanticIntent.REPLACE_RELATIONSHIP, "Ari", "Ari now normal friend", PersonRelationship.BEST_FRIEND, replacementRelation = PersonRelationship.FRIEND)
        ))
        brain.executeFinalTurnPlan(plan)
        val active = repository.allActive().filter { it.entityName == "Ari" }
        assertEquals(setOf(id), active.map { it.entityId }.toSet())
        assertFalse(active.any { it.fact.contains("best friend") })
        assertTrue(active.any { it.fact.contains("Zopy's friend") })
    }

    @Test fun uniqueDurablePersonResolvesPronounButAmbiguousPeopleClarify() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        repository.addPersonRelationship("Ari", PersonRelationship.FRIEND)
        val brain = MemoryBrainCoordinator(repository)
        val proposal = frame(MemorySemanticIntent.ADD_LINKED_FACT, evidence = "makes gaming videos", fact = "Ari makes gaming videos")
        assertEquals("Ari", brain.prepareFinalTurn("makes gaming videos", listOf(proposal)).operations.single().person)
        repository.addPersonRelationship("Bea", PersonRelationship.FRIEND)
        val ambiguous = brain.prepareFinalTurn("makes gaming videos", listOf(proposal.copy(person = null)))
        assertTrue(ambiguous.requiresClarification)
    }

    @Test fun verifiedRenameSurvivesRepeatedReconciliationAndNewFactsReuseIdentity() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        repository.addPersonRelationship("Kareem", PersonRelationship.BEST_FRIEND)
        val originalId = repository.allActive().first().entityId
        assertTrue(repository.renamePerson("Kareem", "Karim"))
        repeat(3) { repository.reconcileUniqueRelationships() }
        val after = repository.allActive()
        assertTrue(after.all { it.entityName == "Karim" })
        assertEquals(setOf(originalId), after.map { it.entityId }.toSet())
        val brain = MemoryBrainCoordinator(repository)
        val plan = brain.prepareFinalTurn("Karim creates videos", listOf(
            frame(MemorySemanticIntent.ADD_LINKED_FACT, "Karim", "Karim creates videos", fact = "Karim creates videos")
        ))
        brain.executeFinalTurnPlan(plan)
        assertEquals(setOf(originalId), repository.allActive().map { it.entityId }.toSet())
        assertFalse(repository.allActive().any { it.entityName == "Kareem" })
    }

    @Test fun questionSafetyOverridesMutatingModelProposal() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val plan = brain.prepareFinalTurn("Which friends do you remember?", listOf(
            frame(MemorySemanticIntent.DELETE_ENTITY, "Ari", "friends remember")
        ))
        assertEquals(MemoryDecision.RECALL, plan.decision)
        brain.executeFinalTurnPlan(plan)
        assertTrue(repository.allActive().isEmpty())
    }

    private fun frame(
        intent: MemorySemanticIntent,
        person: String? = null,
        evidence: String,
        relationship: PersonRelationship? = null,
        replacement: String? = null,
        replacementRelation: PersonRelationship? = null,
        fact: String? = null,
        temporal: MemoryTemporalScope = MemoryTemporalScope.CURRENT
    ) = MemorySemanticFrame(intent, person, replacement, relationship, replacementRelation, temporal,
        fact, confidence = .96, evidence = evidence)
}
