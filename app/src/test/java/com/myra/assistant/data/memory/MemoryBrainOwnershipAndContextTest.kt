package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryBrainOwnershipAndContextTest {
    @Test fun explicitForgetIsExecutedByCoordinatorAndVerified() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        NaturalMemoryExtractor.extract("Mera friend Kareem hai").forEach { repository.saveGrounded(it) }
        NaturalMemoryExtractor.extract("Main Kareem ke saath Manali travel gaya tha")
            .forEach { repository.saveGrounded(it) }

        val command = MemoryCommandParser.parse("Forget Kareem") as MemoryCommand.Forget
        val outcome = brain.processCommand(command)

        assertTrue(outcome is MemoryBrainOutcome.Deleted)
        assertTrue((outcome as MemoryBrainOutcome.Deleted).succeeded)
        assertTrue(repository.allActive().isEmpty())
        assertEquals(MemoryDecision.DELETE, MemoryWorkingContext.lastTransaction?.type)
        assertEquals(MemoryTransactionStatus.SUCCEEDED, MemoryWorkingContext.lastTransaction?.status)
        MemoryWorkingContext.clear()
    }

    @Test fun explicitEditUsesTheSingleRecalledMemoryAsHumanWorkingContext() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        repository.saveGrounded(MemoryCandidate(
            MemoryCategory.PREFERENCE,
            "Zopy prefers short answers",
            "preference:response_style",
            MemorySensitivity.LOW,
            .96,
            provenance = MemoryProvenance.USER_DIRECT_STATEMENT
        ))

        brain.recall("short answers", 1)
        val command = MemoryCommandParser.parse(
            "Update this memory to Zopy prefers detailed answers"
        ) as MemoryCommand.Edit
        val outcome = brain.processCommand(command)

        assertTrue(outcome is MemoryBrainOutcome.Mutated)
        assertTrue((outcome as MemoryBrainOutcome.Mutated).result is MemoryWriteResult.Saved)
        val active = repository.allActive().filter { it.stableKey == "preference:response_style" }
        assertEquals(1, active.size)
        assertEquals("Zopy prefers detailed answers", active.single().fact)
        assertEquals(MemoryProvenance.USER_EXPLICIT_MEMORY_COMMAND.name, active.single().provenance)
        assertEquals(MemoryTransactionStatus.SUCCEEDED, MemoryWorkingContext.lastTransaction?.status)
        MemoryWorkingContext.clear()
    }

    @Test fun thisMemoryEditNeverGuessesWithoutVerifiedRecentMemory() = runBlocking {
        MemoryWorkingContext.clear()
        val brain = MemoryBrainCoordinator(MemoryRepository(FakeMemoryDao()))
        val command = MemoryCommandParser.parse(
            "Update this memory to Zopy prefers detailed answers"
        ) as MemoryCommand.Edit

        val outcome = brain.processCommand(command)

        assertTrue(outcome is MemoryBrainOutcome.Rejected)
        assertTrue((outcome as MemoryBrainOutcome.Rejected).reason.contains("ambiguous", true))
        MemoryWorkingContext.clear()
    }

    @Test fun verifiedRenameEntryOwnsLinkedIdentityMigration() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        NaturalMemoryExtractor.extract("Mera friend Kareem hai").forEach { repository.saveGrounded(it) }
        NaturalMemoryExtractor.extract("Main Kareem ke saath Manali aur Kerala travel gaya tha")
            .forEach { repository.saveGrounded(it) }
        val entityId = repository.allActive().first { it.category == MemoryCategory.PERSON.name }.entityId

        val outcome = brain.processPersonRename(BestFriendNameCorrection("Kareem", "Karim"))

        assertTrue(outcome is MemoryBrainOutcome.Mutated)
        assertTrue((outcome as MemoryBrainOutcome.Mutated).result is MemoryWriteResult.Saved)
        val active = repository.allActive()
        assertEquals(setOf(entityId), active.map { it.entityId }.toSet())
        assertTrue(active.all { it.fact.contains("Karim") && !it.fact.contains("Kareem") })
        assertEquals(MemoryProvenance.VERIFIED_MEMORY_CORRECTION.name,
            active.first { it.category == MemoryCategory.PERSON.name }.provenance)
        MemoryWorkingContext.clear()
    }

    @Test fun verifiedRenamePreservesExtractorIdentityAcrossAllLinkedFacts() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        PersonLinkedMemoryExtractor.extractAll(
            "Mera best friend Kareem hai, uska gaming channel hai aur gaming videos banata hai"
        ).forEach { repository.saveGrounded(it) }
        val before = repository.allActive()
        val entityId = before.first().entityId
        assertEquals(3, before.size)
        assertEquals(setOf(entityId), before.map { it.entityId }.toSet())

        val outcome = brain.processFinalTurn("Kareem nahi, Karim")

        assertTrue(outcome is MemoryBrainOutcome.Mutated)
        val active = repository.allActive()
        assertEquals(3, active.size)
        assertEquals(setOf(entityId), active.map { it.entityId }.toSet())
        assertTrue(active.all { it.entityName == "Karim" && it.fact.contains("Karim") })
        assertTrue(repository.relevant("Kareem", 10).isEmpty())
        assertEquals(3, repository.relevant("Karim", 10).size)
        MemoryWorkingContext.clear()
    }

    @Test fun deletingOneLinkedFactKeepsPersonButWholePersonDeleteRemovesOnlyThatUnit() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        PersonLinkedMemoryExtractor.extractAll(
            "Mera best friend Kareem hai aur uska gaming channel hai"
        ).forEach { repository.saveGrounded(it) }
        repository.saveGrounded(MemoryCandidate(
            MemoryCategory.PROJECT, "Zopy builds LYRA", "project:lyra",
            MemorySensitivity.LOW, .98
        ))
        val channel = repository.allActive().single { it.fact.contains("gaming channel") }

        assertTrue(repository.forget(channel.id))
        assertTrue(repository.allActive().any { it.entityName == "Kareem" })

        assertTrue(repository.forgetMatching("Kareem"))
        assertTrue(repository.allActive().none { it.entityName == "Kareem" })
        assertTrue(repository.allActive().any { it.stableKey == "project:lyra" })
    }

    @Test fun genericRememberQuestionMarksReturnedRowsAsActuallyRecalled() = runBlocking {
        MemoryWorkingContext.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        repository.saveGrounded(MemoryCandidate(
            MemoryCategory.PROJECT,
            "Zopy builds LYRA Android",
            "project:lyra",
            MemorySensitivity.LOW,
            .96
        ))
        assertEquals(0L, repository.allActive().single().lastRecalledAt)

        val outcome = brain.processCommand(MemoryCommand.Read(), 5) as MemoryBrainOutcome.Recalled

        assertEquals(1, outcome.rows.size)
        assertTrue(repository.allActive().single().lastRecalledAt > 0L)
        assertEquals(outcome.rows.single().id, MemoryWorkingContext.recentMemoryId)
        MemoryWorkingContext.clear()
    }

    @Test fun explicitEditParserIsMemoryScopedAndDoesNotStealOrdinaryUpdates() {
        assertTrue(MemoryCommandParser.parse(
            "Update this memory to Zopy prefers detailed answers"
        ) is MemoryCommand.Edit)
        assertTrue(MemoryCommandParser.parse(
            "Is memory ko update karo Zopy prefers detailed answers"
        ) is MemoryCommand.Edit)
        assertNull(MemoryCommandParser.parse("Update my Android app"))
        assertNull(MemoryCommandParser.parse("Feature update nahi ho paya"))
    }

    @Test fun coordinatorOwnsFinalCorrectionClassificationAndQuestionsStayRecall() {
        MemoryWorkingContext.clear()
        MemoryWorkingContext.person("Kareem")
        val brain = MemoryBrainCoordinator(MemoryRepository(FakeMemoryDao()))

        val correction = brain.assessFinalTurn("Kareem nahi, Karim")
        assertEquals(MemoryDecision.UPDATE, correction.decision)
        assertEquals(BestFriendNameCorrection("Kareem", "Karim"), correction.correction)

        assertEquals(MemoryDecision.RECALL, brain.assessFinalTurn("Kiska naam update nahi ho paya?").decision)
        assertEquals(MemoryDecision.IGNORE, brain.assessFinalTurn("Feature update nahi ho paya").decision)
        MemoryWorkingContext.clear()
    }

    @Test fun preFinalDetectionIsClassificationOnlyAndCannotPersist() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        assertTrue(brain.needsCorrectionClarification("Kareem nahi"))
        assertTrue(repository.allActive().isEmpty())
    }

    @Test fun passivePrivacyControlsCanDisableAppAndContentLearningIndependently() {
        assertFalse(PassiveMemoryLearningPolicy.allows(
            BehaviorObservationKind.APP_USAGE, appLearningEnabled = false, contentLearningEnabled = true
        ))
        assertTrue(PassiveMemoryLearningPolicy.allows(
            BehaviorObservationKind.YOUTUBE_CHANNEL, appLearningEnabled = false, contentLearningEnabled = true
        ))
        assertTrue(PassiveMemoryLearningPolicy.allows(
            BehaviorObservationKind.APP_USAGE, appLearningEnabled = true, contentLearningEnabled = false
        ))
        assertFalse(PassiveMemoryLearningPolicy.allows(
            BehaviorObservationKind.CONTENT_TOPIC, appLearningEnabled = true, contentLearningEnabled = false
        ))
    }
}
