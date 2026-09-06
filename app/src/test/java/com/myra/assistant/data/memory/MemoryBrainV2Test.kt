package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryBrainV2Test {
    @Test fun lowRiskNaturalFactsAreSilentGroundedCandidates() {
        val preference = NaturalMemoryExtractor.extract("Mujhe short answers pasand hain")
        val project = NaturalMemoryExtractor.extract("Main LYRA Android project bana raha hoon")
        assertTrue(preference.isNotEmpty())
        assertTrue(project.isNotEmpty())
        assertTrue((preference + project).all { it.provenance == MemoryProvenance.USER_DIRECT_STATEMENT })
    }

    @Test fun questionsNeverBecomeMutation() {
        assertEquals(MemoryDecision.RECALL, MemoryIntentClassifier.decision("Kiska naam update nahi ho paya?"))
        assertEquals(MemoryDecision.RECALL, MemoryIntentClassifier.decision("Kaunsa memory delete hua?"))
        assertNotEquals(MemoryDecision.UPDATE, MemoryIntentClassifier.decision("Feature update nahi ho paya"))
        assertNotEquals(MemoryDecision.UPDATE, MemoryIntentClassifier.decision("Naam update nahi ho paya"))
    }

    @Test fun prohibitedSecretCannotPersist() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val result = repository.saveGrounded(MemoryCandidate(MemoryCategory.IDENTITY,
            "My OTP is 123456", "identity:otp", MemorySensitivity.PROHIBITED, 1.0))
        assertTrue(result is MemoryWriteResult.Rejected)
        assertTrue(repository.allActive().isEmpty())
    }

    @Test fun personAndTravelShareStableIdentity() {
        val person = NaturalMemoryExtractor.extract("Mera friend Kareem hai").single()
        val trip = NaturalMemoryExtractor.extract("Main Kareem ke saath Manali aur Kerala travel gaya tha").single()
        assertEquals(MemoryCategory.PERSON, person.category)
        assertEquals(MemoryCategory.LIFE_EVENT, trip.category)
        assertEquals(person.entityId, trip.entityId)
    }

    @Test fun personRenameKeepsLinkedLifeEventAndStableIdentity() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        NaturalMemoryExtractor.extract("Mera friend Kareem hai").forEach { repository.saveGrounded(it) }
        NaturalMemoryExtractor.extract("Main Kareem ke saath Manali aur Kerala travel gaya tha").forEach { repository.saveGrounded(it) }
        val beforeId = repository.allActive().first { it.category == MemoryCategory.PERSON.name }.entityId
        assertTrue(repository.renamePerson("Kareem", "Karim"))
        val rows = repository.allActive()
        assertEquals(setOf(beforeId), rows.map { it.entityId }.toSet())
        assertTrue(rows.all { it.fact.contains("Karim") && !it.fact.contains("Kareem") })
        assertEquals(2, repository.relevant("Karim", 10).size)
        assertTrue(repository.relevant("Kareem", 10).isEmpty())
    }

    @Test fun deletingLifeEventLeavesPersonButDeletingPersonRemovesLinkedUnit() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        NaturalMemoryExtractor.extract("Mera friend Kareem hai").forEach { repository.saveGrounded(it) }
        NaturalMemoryExtractor.extract("Main Kareem ke saath Manali travel gaya tha").forEach { repository.saveGrounded(it) }
        val rows = repository.allActive()
        assertTrue(repository.forget(rows.first { it.category == MemoryCategory.LIFE_EVENT.name }.id))
        assertEquals(1, repository.allActive().size)
        assertTrue(repository.forgetFromSettings(repository.allActive().single()))
        assertTrue(repository.allActive().isEmpty())
    }

    @Test fun contradictionSupersedesOldPreference() = runBlocking {
        val dao = FakeMemoryDao(); val repository = MemoryRepository(dao)
        repository.saveGrounded((AutomaticMemoryChangeParser.parse("Give me short answers") as AutomaticMemoryChange.Save).candidate)
        repository.saveGrounded((AutomaticMemoryChangeParser.parse("Actually give me detailed answers") as AutomaticMemoryChange.Save).candidate)
        assertEquals(1, repository.allActive().count { PreferenceMemoryIdentity.isResponseVerbosity(it) })
        assertTrue(dao.all().any { it.lifecycleStatus == MemoryLifecycleStatus.SUPERSEDED.name })
    }

    @Test fun recallOnlyMarksActuallyReturnedRows() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        repository.saveGrounded(MemoryCandidate(MemoryCategory.PROJECT, "Zopy builds LYRA Android",
            "project:lyra", MemorySensitivity.LOW, .95))
        val before = repository.allActive().single()
        assertEquals(0L, before.lastRecalledAt)
        repository.relevant("LYRA Android project", 5)
        assertTrue(repository.allActive().single().lastRecalledAt > 0L)
    }

    @Test fun explicitRememberDoesNotNeedSecondPermission() = runBlocking {
        val brain = MemoryBrainCoordinator(MemoryRepository(FakeMemoryDao()))
        val outcome = brain.processFinalTurn("Yaad rakho mujhe astronomy pasand hai")
        assertTrue(outcome is MemoryBrainOutcome.Mutated)
        assertTrue((outcome as MemoryBrainOutcome.Mutated).result is MemoryWriteResult.Saved)
        assertTrue(outcome.explicit)
    }
}
