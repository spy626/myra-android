package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiriMemoryArchitectureTest {
    @Test fun semanticPredicateMayBeTranslatedWhileSourceSpanStaysAuthoritative() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        val e = evidence(1, "मुझे छोटे जवाब पसंद हैं।", "Mujhe chote jawab pasand hain.")
        val frame = fact("Zopy prefers concise responses", "communication:response_length", "Mujhe chote jawab pasand hain.")
        assertSaved(owner, e, frame)
        assertEquals("Zopy prefers concise responses", store.semantic.single { it.active }.statement)
    }

    @Test fun currentPreferenceSupersedesOnlySameDimensionAndPreservesHistory() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        assertSaved(owner, evidence(2, "I prefer concise replies", "I prefer concise replies"), fact("Zopy prefers concise replies", "response_length", "I prefer concise replies"))
        assertSaved(owner, evidence(3, "I like a warm tone", "I like a warm tone"), fact("Zopy prefers a warm tone", "response_tone", "I like a warm tone"))
        assertSaved(owner, evidence(4, "Now I prefer detailed replies", "Now I prefer detailed replies"), fact("Zopy prefers detailed replies", "response_length", "Now I prefer detailed replies", MemorySemanticIntent.SUPERSEDE_FACT))
        assertEquals(2, store.semantic.count { it.active })
        assertEquals(1, store.semantic.count { !it.active && it.semanticKey == "response_length" })
        assertTrue(store.semantic.single { it.active && it.semanticKey == "response_tone" }.statement.contains("warm"))
    }

    @Test fun peopleAreAdditiveAndRelationshipTypesRemainDistinct() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        relationship(owner, evidence(5, "Mera dost Samir hai", "Mera dost Samir hai", listOf("Samir")), "Samir", PersonRelationship.FRIEND)
        relationship(owner, evidence(6, "रिहान मेरा बहुत अच्छा दोस्त है", "Rihan mera bahut accha dost hai", listOf("Rihan")), "Rihan", PersonRelationship.GOOD_FRIEND)
        val friends = owner.recall("friends", type = MemoryRecallType.FRIENDS).rows
        assertEquals(setOf("Samir", "Rihan"), friends.mapNotNull { it.entityName }.toSet())
        assertEquals(0, owner.recall("best", type = MemoryRecallType.BEST_FRIEND).rows.size)
        assertEquals(2, store.people.size)
    }

    @Test fun renameKeepsIdentityAndAmbiguousRenameCannotMutate() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        relationship(owner, evidence(7, "Mera dost Samir hai", "Mera dost Samir hai", listOf("Samir")), "Samir", PersonRelationship.FRIEND)
        val id = store.people.values.single().entityId
        val renameEvidence = evidence(8, "Samir ka naam Sameer hai", "Samir ka naam Sameer hai", listOf("Samir", "Sameer"))
        val rename = MemorySemanticFrame(MemorySemanticIntent.RENAME_ENTITY, person = "Samir", replacementPerson = "Sameer",
            confidence = .96, sourceSpan = renameEvidence.displayText, sourceTurnId = 8,
            criticalLiterals = listOf("Samir", "Sameer"))
        assertSaved(owner, renameEvidence, rename)
        assertEquals(id, store.people.values.single().entityId)
        assertEquals("Sameer", store.people.values.single().canonicalName)
        assertTrue(owner.recall("friends", type = MemoryRecallType.FRIENDS).rows.single().entityName == "Sameer")

        val ambiguousEvidence = evidence(9, "Uska naam Rayan hai", "Uska naam Rayan hai", listOf("Rayan"))
        val ambiguous = rename.copy(person = null, replacementPerson = "Rayan", sourceSpan = ambiguousEvidence.displayText, sourceTurnId = 9, criticalLiterals = listOf("Rayan"))
        val plan = owner.prepareFinalTurn(ambiguousEvidence, listOf(ambiguous))
        assertEquals(MemoryDecision.NEEDS_CLARIFICATION, plan.decision)
        assertEquals("Sameer", store.people.values.single().canonicalName)
    }

    @Test fun relationshipRemovalPreservesPersonAndEpisodes() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        relationship(owner, evidence(10, "Mera dost Ishan hai", "Mera dost Ishan hai", listOf("Ishan")), "Ishan", PersonRelationship.FRIEND)
        val episodeEvidence = evidence(11, "Aaj Ishan ke saath chess khela", "Aaj Ishan ke saath chess khela", listOf("Ishan"))
        val episode = MemorySemanticFrame(MemorySemanticIntent.ADD_EPISODE, confidence = .95,
            temporalScope = MemoryTemporalScope.HISTORICAL, sourceSpan = episodeEvidence.displayText,
            sourceTurnId = 11, criticalLiterals = listOf("Ishan"), episode = EpisodicMemoryPayload("ACTIVITY", "Played chess with Ishan", listOf("Ishan")))
        assertSaved(owner, episodeEvidence, episode)
        val removeEvidence = evidence(12, "Ishan is no longer my friend", "Ishan is no longer my friend", listOf("Ishan"))
        val remove = MemorySemanticFrame(MemorySemanticIntent.REMOVE_RELATIONSHIP, person = "Ishan",
            relationship = PersonRelationship.FRIEND, confidence = .96, sourceSpan = removeEvidence.displayText,
            sourceTurnId = 12, criticalLiterals = listOf("Ishan"))
        val plan = owner.prepareFinalTurn(removeEvidence, listOf(remove))
        assertTrue(owner.executeFinalTurnPlan(plan, removeEvidence) is MemoryBrainOutcome.Deleted)
        assertEquals(1, store.people.values.count { it.active })
        assertEquals(1, store.episodes.size)
        assertTrue(owner.recall("friends", type = MemoryRecallType.FRIENDS).rows.isEmpty())
    }

    @Test fun episodeNeverInfersRelationshipAndCompoundTurnCanStoreBoth() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        val e = evidence(13, "Aaj Tara ke saath game khela", "Aaj Tara ke saath game khela", listOf("Tara"))
        val episode = MemorySemanticFrame(MemorySemanticIntent.ADD_EPISODE, confidence = .95, temporalScope = MemoryTemporalScope.HISTORICAL,
            sourceSpan = e.displayText, sourceTurnId = 13, criticalLiterals = listOf("Tara"), episode = EpisodicMemoryPayload("ACTIVITY", "Played a game with Tara", listOf("Tara")))
        assertSaved(owner, e, episode)
        assertTrue(store.relationships.isEmpty())

        val compound = evidence(14, "Aaj Mira ke saath game khela, vo meri dost hai", "Aaj Mira ke saath game khela, vo meri dost hai", listOf("Mira"))
        val frames = listOf(
            episode.copy(sourceSpan = "Aaj Mira ke saath game khela", sourceTurnId = 14, criticalLiterals = listOf("Mira"), episode = EpisodicMemoryPayload("ACTIVITY", "Played a game with Mira", listOf("Mira"))),
            MemorySemanticFrame(MemorySemanticIntent.ADD_RELATIONSHIP, person = "Mira", relationship = PersonRelationship.FRIEND,
                confidence = .96, sourceSpan = "vo meri dost hai", sourceTurnId = 14, criticalLiterals = listOf("Mira"))
        )
        val plan = owner.prepareFinalTurn(compound, frames)
        assertTrue(owner.executeFinalTurnPlan(plan, compound) is MemoryBrainOutcome.Mutated)
        assertEquals(2, store.episodes.size); assertEquals(1, store.relationships.count { it.active })
    }

    @Test fun temporaryQuestionSecretAndStaleTurnCannotWrite() = runBlocking {
        AiriWorkingMemory.clear(); val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        val tempEvidence = evidence(15, "Sirf aaj concise replies dena", "Sirf aaj concise replies dena")
        val temp = fact("Use concise replies today", "response_length", tempEvidence.displayText).copy(temporalScope = MemoryTemporalScope.TEMPORARY, sourceTurnId = 15)
        val tempPlan = owner.prepareFinalTurn(tempEvidence, listOf(temp))
        assertTrue(owner.executeFinalTurnPlan(tempPlan, tempEvidence) is MemoryBrainOutcome.Transient)
        assertTrue(store.semantic.isEmpty())

        val q = evidence(16, "What are my preferences?", "What are my preferences?")
        val mutationOnQuestion = fact("invented", "preference", q.displayText).copy(sourceTurnId = 16)
        val mixed = owner.prepareFinalTurn(q, listOf(MemorySemanticFrame(MemorySemanticIntent.RECALL, fact = "", stableKey = "GENERAL", confidence = .99, sourceSpan = q.displayText, sourceTurnId = 16), mutationOnQuestion))
        assertEquals(MemoryDecision.REJECT, mixed.decision)

        val secret = evidence(17, "My OTP is 123456", "My OTP is 123456")
        assertEquals(MemoryDecision.REJECT, owner.prepareFinalTurn(secret, listOf(fact("Authentication code 123456", "credential", secret.displayText).copy(sourceTurnId = 17, criticalLiterals = listOf("123456")))).decision)

        val valid = evidence(18, "I enjoy astronomy", "I enjoy astronomy")
        val plan = owner.prepareFinalTurn(valid, listOf(fact("Zopy enjoys astronomy", "interest", valid.displayText).copy(sourceTurnId = 18)))
        evidence(19, "newer turn", "newer turn")
        assertTrue(owner.executeFinalTurnPlan(plan, valid) is MemoryBrainOutcome.Rejected)
        assertTrue(store.semantic.isEmpty())
    }

    @Test fun conversationTruthProjectionContextRegistryTaskMemoryAndBufferFollowAiriContracts() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        (20L..24L).forEach { owner.captureConversation(evidence(it, "turn $it", "turn $it"), "reply $it") }
        assertEquals(10, store.conversationCount("test")); assertEquals(4, store.promptProjection("test", 4).size)
        val registry = LyraContextRegistry(perBucketLimit = 2)
        assertTrue(registry.ingest(ContextEntry("screen", "one", 1, 1), ContextMutation.REPLACE_SELF))
        registry.ingest(ContextEntry("person", "Asha", 1, 1), ContextMutation.REPLACE_SELF)
        registry.ingest(ContextEntry("screen", "two", 2, 2), ContextMutation.REPLACE_SELF)
        assertEquals("Asha", registry.bucket("person").single().value)
        assertFalse(registry.ingest(ContextEntry("screen", "stale", 1, 1), ContextMutation.APPEND_SELF))
        val taskStore = WorkingTaskMemoryStore(); val task = WorkingTaskMemory("t", "goal", TaskMemoryStatus.ACTIVE, "step", List(14) { "f$it" }, blockers = emptyList(), nextStep = null, plan = emptyList(), workingAssumptions = emptyList(), lastFailureReason = null, completionCriteria = emptyList(), foregroundContext = null, lastAction = null, expectedResult = null, verificationState = null, sourceTurnId = 3, contextGeneration = 3)
        assertTrue(taskStore.update(task) is TaskMemoryUpdate.Updated); assertEquals(10, taskStore.snapshot()!!.confirmedFacts.size)
        assertEquals(TaskMemoryUpdate.IgnoredStale, taskStore.update(task.copy(sourceTurnId = 2, contextGeneration = 2)))
        val buffer = FinalTranscriptTurnBuffer(); buffer.append("one"); assertEquals("one", buffer.flush()); buffer.append("two"); assertEquals("two", buffer.flush())
    }

    @Test fun behaviorNeedsRepeatedEvidenceAndDecayNeverTouchesExplicitFacts() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val learner = BehaviorMemoryLearner(store)
        repeat(4) { learner.observe(BehaviorSignal(BehaviorObservationKind.APP_USAGE, "Maps", "s$it", it * BehaviorMemoryLearner.DAY_MS)) }
        assertEquals("OBSERVED", store.behavior.values.single().state)
        learner.observe(BehaviorSignal(BehaviorObservationKind.APP_USAGE, "Maps", "s4", 4 * BehaviorMemoryLearner.DAY_MS))
        assertEquals("ACTIVE", store.behavior.values.single().state)
        val owner = MemoryBrainCoordinator(store); val e = evidence(30, "I prefer calm replies", "I prefer calm replies")
        assertSaved(owner, e, fact("Zopy prefers calm replies", "tone", e.displayText))
        learner.decay(100 * BehaviorMemoryLearner.DAY_MS)
        assertEquals(1, store.semantic.count { it.active })
    }

    private suspend fun relationship(owner: MemoryBrainCoordinator, e: AuthoritativeMemoryTurnEvidence, name: String, type: PersonRelationship) {
        assertSaved(owner, e, MemorySemanticFrame(MemorySemanticIntent.ADD_RELATIONSHIP, person = name, relationship = type,
            confidence = .96, sourceSpan = e.displayText, sourceTurnId = e.turnId, criticalLiterals = listOf(name)))
    }
    private suspend fun assertSaved(owner: MemoryBrainCoordinator, e: AuthoritativeMemoryTurnEvidence, frame: MemorySemanticFrame) {
        val plan = owner.prepareFinalTurn(e, listOf(frame.copy(sourceTurnId = e.turnId)))
        val outcome = owner.executeFinalTurnPlan(plan, e)
        assertTrue("plan=${plan.decision}/${plan.rejectionReason} outcome=$outcome", outcome is MemoryBrainOutcome.Mutated)
    }
    private fun fact(statement: String, key: String, span: String, intent: MemorySemanticIntent = MemorySemanticIntent.ADD_FACT) = MemorySemanticFrame(intent, temporalScope = MemoryTemporalScope.CURRENT, fact = statement, category = MemoryCategory.PREFERENCE, stableKey = key, confidence = .96, sourceSpan = span)
    private fun evidence(turn: Long, canonical: String, display: String, names: List<String> = emptyList()): AuthoritativeMemoryTurnEvidence {
        AiriMemoryRuntime.claimTurn("test", turn)
        return AuthoritativeMemoryTurnEvidence(turn, canonical, display, names, names, "test", "test:$turn", turn)
    }
}
