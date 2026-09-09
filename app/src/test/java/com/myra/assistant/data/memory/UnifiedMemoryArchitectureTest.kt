package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UnifiedMemoryArchitectureTest {
    @Test fun semanticParaphraseDoesNotParticipateInAuthorization() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val spoken = "Mujhe concise responses pasand hain"
        val frame = fact("The user favors brief replies", "reply_length", spoken)

        val plan = brain.prepareFinalTurn(spoken, listOf(frame))

        assertEquals(MemoryDecision.SAVE, plan.decision)
        assertTrue(brain.executeFinalTurnPlan(plan) is MemoryBrainOutcome.Mutated)
        assertEquals("The user favors brief replies", repository.allActive().single().fact)
    }

    @Test fun wrongAndStaleTurnsNeverWrite() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val final = AuthoritativeMemoryTurnEvidence(81, "I enjoy astronomy", "I enjoy astronomy", sessionId = "s")
        UnifiedMemoryRuntime.claimTurn("s", 81)
        val wrong = brain.prepareFinalTurn(final, listOf(fact("User enjoys astronomy", "interest", "I enjoy astronomy").copy(sourceTurnId = 80)))
        assertEquals(MemoryDecision.REJECT, wrong.decision)

        val valid = brain.prepareFinalTurn(final, listOf(fact("User enjoys astronomy", "interest", "I enjoy astronomy").copy(sourceTurnId = 81)))
        UnifiedMemoryRuntime.claimTurn("s", 82)
        assertEquals(MemoryBrainOutcome.Ignored, brain.executeFinalTurnPlan(valid))
        assertTrue(repository.allActive().isEmpty())
    }

    @Test fun questionAndSecretsRemainReadOnlyAndExact() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val question = "Do I enjoy astronomy?"
        val questionPlan = brain.prepareFinalTurn(question, listOf(fact("User enjoys astronomy", "interest", question)))
        assertEquals(MemoryDecision.RECALL, questionPlan.decision)

        val secret = "My API key is 123456"
        val secretPlan = brain.prepareFinalTurn(secret, listOf(fact(secret, "credential", secret)))
        assertEquals(MemoryDecision.REJECT, secretPlan.decision)
        assertTrue(repository.allActive().isEmpty())
    }

    @Test fun temporaryInstructionUsesExpiringContextAndNeverRoom() = runBlocking {
        UnifiedMemoryRuntime.contexts.clear()
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val spoken = "Only today, keep replies concise"
        val plan = brain.prepareFinalTurn(spoken, listOf(fact(
            "Use concise replies today", "reply_length", spoken, MemoryTemporalScope.TEMPORARY
        )))

        assertEquals(MemoryDecision.IGNORE, plan.decision)
        assertEquals(MemoryBrainOutcome.Ignored, brain.executeFinalTurnPlan(plan))
        assertTrue(repository.allActive().isEmpty())
        assertEquals("Use concise replies today", UnifiedMemoryRuntime.contexts.bucket("temporary_instruction").single().value)
    }

    @Test fun contextSourcesReplaceAndAppendWithoutCrossSourceErasure() {
        var now = 100L
        val registry = LyraContextRegistry(perBucketLimit = 2, clock = { now })
        assertTrue(registry.ingest(ContextEntry("screen", "one", 1, 1), ContextMutation.REPLACE_SELF))
        assertTrue(registry.ingest(ContextEntry("person", "Asha", 1, 1), ContextMutation.REPLACE_SELF))
        assertTrue(registry.ingest(ContextEntry("screen", "two", 2, 2), ContextMutation.REPLACE_SELF))
        assertEquals("Asha", registry.bucket("person").single().value)
        assertEquals("two", registry.bucket("screen").single().value)
        assertFalse(registry.ingest(ContextEntry("screen", "stale", 1, 1), ContextMutation.APPEND_SELF))
        now = 200
        registry.ingest(ContextEntry("temporary", "expired", 2, 2, expiresAt = 201), ContextMutation.REPLACE_SELF)
        now = 202
        assertTrue(registry.bucket("temporary").isEmpty())
    }

    @Test fun taskMemoryRejectsStaleUpdatesAndBoundsLists() {
        val store = WorkingTaskMemoryStore()
        val current = task(9, 9, List(14) { "fact-$it" })
        assertTrue(store.update(current) is TaskMemoryUpdate.Updated)
        assertEquals(10, store.snapshot()!!.confirmedFacts.size)
        assertEquals(TaskMemoryUpdate.IgnoredStale, store.update(task(8, 8, emptyList())))
        assertEquals(9, store.snapshot()!!.sourceTurnId)
    }

    @Test fun transcriptBufferFailureCannotPoisonFollowingTurnAndTruthProjectionIsBounded() {
        val buffer = FinalTranscriptTurnBuffer()
        buffer.append("first")
        assertEquals("first", buffer.flush())
        buffer.append("second")
        assertEquals("second", buffer.flush())
        val truth = ConversationTruthStore(2)
        (1L..3L).forEach { truth.commit(ConversationTruthTurn(it, "u$it", "turn $it", null)) }
        truth.commit(ConversationTruthTurn(3, "u3", "duplicate", null))
        assertEquals(3, truth.truth().size)
        assertEquals(listOf(2L, 3L), truth.promptProjection().map { it.turnId })
    }

    @Test fun episodeIsSeparateFromRelationshipAndRetrievesByType() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val spoken = "Today I played chess with Leena"
        val frame = MemorySemanticFrame(
            intent = MemorySemanticIntent.ADD_EPISODE, person = "Leena", sourceSpan = spoken,
            criticalLiterals = listOf("Leena"), confidence = .95,
            temporalScope = MemoryTemporalScope.HISTORICAL,
            episode = EpisodicMemoryPayload("ACTIVITY", "Played chess with Leena", listOf("Leena"))
        )
        val outcome = brain.executeFinalTurnPlan(brain.prepareFinalTurn(spoken, listOf(frame)))
        assertTrue(outcome is MemoryBrainOutcome.Mutated)
        assertEquals(1, brain.recall("last activity", type = MemoryRecallType.EPISODES).rows.size)
        assertTrue(brain.recall("friends", type = MemoryRecallType.FRIENDS).rows.isEmpty())
    }

    @Test fun structuredGoalPersistsAndRetrievalIsBounded() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val spoken = "My goal is to ship Orion"
        val frame = MemorySemanticFrame(
            intent = MemorySemanticIntent.ADD_GOAL, stableKey = "ship_orion", sourceSpan = spoken,
            confidence = .95, temporalScope = MemoryTemporalScope.CURRENT,
            goal = GoalMemoryPayload("Ship Orion", "Complete and ship Orion", priority = 2)
        )
        assertTrue(brain.executeFinalTurnPlan(brain.prepareFinalTurn(spoken, listOf(frame))) is MemoryBrainOutcome.Mutated)
        assertEquals(MemoryCategory.GOAL.name, brain.recall("goal", limit = 1, type = MemoryRecallType.GOALS).rows.single().category)
    }

    private fun fact(
        semanticFact: String,
        key: String,
        sourceSpan: String,
        temporal: MemoryTemporalScope = MemoryTemporalScope.CURRENT
    ) = MemorySemanticFrame(
        intent = MemorySemanticIntent.ADD_FACT,
        category = MemoryCategory.PREFERENCE,
        fact = semanticFact,
        stableKey = key,
        temporalScope = temporal,
        confidence = .95,
        evidence = "A deliberately unrelated model explanation",
        sourceSpan = sourceSpan
    )

    private fun task(turn: Long, generation: Long, facts: List<String>) = WorkingTaskMemory(
        taskId = "task", goal = "goal", status = TaskMemoryStatus.ACTIVE, currentStep = "step",
        confirmedFacts = facts, blockers = emptyList(), nextStep = null, plan = emptyList(),
        workingAssumptions = emptyList(), lastFailureReason = null, completionCriteria = emptyList(),
        foregroundContext = null, lastAction = null, expectedResult = null,
        verificationState = null, sourceTurnId = turn, contextGeneration = generation
    )
}
