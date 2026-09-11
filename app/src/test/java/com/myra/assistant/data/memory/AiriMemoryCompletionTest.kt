package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiriMemoryCompletionTest {
    @Test fun parsedGeminiRelationshipRecoversOneMissingCurrentTurnPerson() = runBlocking {
        val e = evidence(101, "Mera dost Leena hai", "Mera dost Leena hai", listOf("Leena"))
        val parsed = GeminiMemoryOperationParser.parse(JSONObject().put("operations", JSONArray().put(
            JSONObject().put("intent", "ADD_RELATIONSHIP").put("relationship", "FRIEND")
                .put("source_span", "Mera dost Leena hai").put("confidence", .96)
                .put("critical_literals", JSONArray().put("Leena"))
        ))).single().copy(sourceTurnId = e.turnId)
        assertNull(parsed.person)
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        val plan = owner.prepareFinalTurn(e, listOf(parsed))
        assertEquals("Leena", plan.operations.single().person)
        assertTrue(owner.executeFinalTurnPlan(plan, e) is MemoryBrainOutcome.Mutated)
        assertEquals("Leena", owner.recall("friends", type = MemoryRecallType.FRIENDS).rows.single().entityName)
    }

    @Test fun saveLikeMissingOrAmbiguousEntityDoesNotBlockAuthoritativeFallback() = runBlocking {
        val owner = MemoryBrainCoordinator(InMemoryAiriMemoryStore())
        val missing = evidence(102, "Someone is my friend", "Someone is my friend")
        val base = MemorySemanticFrame(MemorySemanticIntent.ADD_RELATIONSHIP,
            relationship = PersonRelationship.FRIEND, sourceSpan = missing.displayText,
            sourceTurnId = missing.turnId, confidence = .95)
        val missingContract = MemoryOperationContractValidator.validateAndRecover(base, missing)
        assertNull(missingContract.reason)
        assertNotNull(missingContract.frame)
        val missingPlan = owner.prepareFinalTurn(missing, listOf(base))
        assertEquals(MemoryDecision.SAVE, missingPlan.decision)
        assertNull(missingPlan.rejectionReason)

        val ambiguous = evidence(103, "Asha and Mira are my friends", "Asha and Mira are my friends")
        val two = base.copy(sourceSpan = ambiguous.displayText, sourceTurnId = ambiguous.turnId,
            criticalLiterals = listOf("Asha", "Mira"))
        val ambiguousContract = MemoryOperationContractValidator.validateAndRecover(two, ambiguous)
        assertNull(ambiguousContract.reason)
        assertNotNull(ambiguousContract.frame)
        val ambiguousPlan = owner.prepareFinalTurn(ambiguous, listOf(two))
        assertEquals(MemoryDecision.SAVE, ambiguousPlan.decision)
        assertNull(ambiguousPlan.rejectionReason)

        val destructiveEvidence = evidence(104, "Rename someone to Zara", "Rename someone to Zara", listOf("Zara"))
        val destructive = MemorySemanticFrame(MemorySemanticIntent.RENAME_ENTITY,
            replacementPerson = "Zara", sourceSpan = destructiveEvidence.displayText,
            sourceTurnId = destructiveEvidence.turnId, confidence = .95, criticalLiterals = listOf("Zara"))
        assertEquals(MemoryFailureReason.MISSING_REQUIRED_ENTITY,
            MemoryOperationContractValidator.validateAndRecover(destructive, destructiveEvidence).reason)
    }

    @Test fun saveLikeFieldsRecoverWhileDestructiveFieldsRemainStrict() {
        val e = evidence(104, "Ravi is my friend", "Ravi is my friend", listOf("Ravi"))
        fun contract(frame: MemorySemanticFrame) = MemoryOperationContractValidator.validateAndRecover(frame, e)
        fun reason(frame: MemorySemanticFrame) = contract(frame).reason
        val common = MemorySemanticFrame(MemorySemanticIntent.ADD_RELATIONSHIP, person = "Ravi",
            sourceSpan = e.displayText, sourceTurnId = e.turnId, confidence = .9)

        assertNull(reason(common))
        assertNull(reason(common.copy(intent = MemorySemanticIntent.ADD_LINKED_FACT)))

        val episode = contract(common.copy(intent = MemorySemanticIntent.ADD_EPISODE))
        assertNull(episode.reason)
        assertEquals("Ravi is my friend", episode.frame!!.episode!!.summary)
        assertEquals("activity", episode.frame!!.episode!!.eventType)

        assertNull(reason(common.copy(intent = MemorySemanticIntent.ADD_GOAL)))
        assertEquals(MemoryFailureReason.MISSING_REQUIRED_REPLACEMENT,
            reason(common.copy(intent = MemorySemanticIntent.RENAME_ENTITY)))
        assertEquals(MemoryFailureReason.MISSING_REQUIRED_RELATIONSHIP,
            reason(common.copy(intent = MemorySemanticIntent.REPLACE_RELATIONSHIP)))
    }

    @Test fun devanagariAndRomanRelationshipsAreAdditiveAndDowngradeSameEntity() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        relationship(owner, evidence(105, "मेरा दोस्त रोहन है", "Mera dost Rohan hai", listOf("Rohan")), "Rohan", PersonRelationship.FRIEND)
        relationship(owner, evidence(106, "Zoya meri bahut acchi dost hai", "Zoya meri bahut acchi dost hai", listOf("Zoya")), "Zoya", PersonRelationship.GOOD_FRIEND)
        assertEquals(2, owner.recall("friends", type = MemoryRecallType.FRIENDS).rows.size)
        val id = store.peopleByName("Zoya").single().entityId
        fun replacement(e: AuthoritativeMemoryTurnEvidence, type: PersonRelationship) = MemorySemanticFrame(
            MemorySemanticIntent.REPLACE_RELATIONSHIP, person = "Zoya", replacementRelationship = type,
            sourceSpan = e.displayText, sourceTurnId = e.turnId, confidence = .96, criticalLiterals = listOf("Zoya"))
        val best = evidence(107, "Zoya is now my best friend", "Zoya is now my best friend", listOf("Zoya"))
        assertTrue(execute(owner, best, replacement(best, PersonRelationship.BEST_FRIEND)) is MemoryBrainOutcome.Mutated)
        assertEquals(1, owner.recall("best", type = MemoryRecallType.BEST_FRIEND).rows.size)
        val normal = evidence(108, "Zoya is now a normal friend", "Zoya is now a normal friend", listOf("Zoya"))
        assertTrue(execute(owner, normal, replacement(normal, PersonRelationship.FRIEND)) is MemoryBrainOutcome.Mutated)
        assertEquals(id, store.peopleByName("Zoya").single().entityId)
        assertEquals(PersonRelationship.FRIEND.name, store.relationships.single { it.active && it.targetEntityId == id }.relationshipType)
    }

    @Test fun strongestCredentialGovernmentAndFinancialSafetyBlocksWritesButNotQuestions() = runBlocking {
        val owner = MemoryBrainCoordinator(InMemoryAiriMemoryStore())
        listOf(
            "My OTP is 739201" to "credential:otp",
            "My Aadhaar number is 1234 5678 9012" to "identity:aadhaar",
            "My PAN number is ABCDE1234F" to "identity:pan_number",
            "My bank account number is 987654321" to "finance:bank_account",
            "My API key is abc-def-secret" to "credential:api_key"
        ).forEachIndexed { index, (text, key) ->
            val e = evidence(110L + index, text, text)
            val frame = fact(text, key, e).copy(criticalLiterals = Regex("[A-Z0-9-]{4,}").findAll(text).map { it.value }.toList())
            assertEquals(MemoryDecision.REJECT, owner.prepareFinalTurn(e, listOf(frame)).decision)
        }
        val q = evidence(120, "What is my passport number?", "What is my passport number?")
        val recall = MemorySemanticFrame(MemorySemanticIntent.RECALL, stableKey = "GENERAL", fact = q.displayText,
            sourceSpan = q.displayText, sourceTurnId = q.turnId, confidence = .99)
        assertEquals(MemoryDecision.RECALL, owner.prepareFinalTurn(q, listOf(recall)).decision)
    }

    @Test fun localFastLaneClassifiesAndRetrievesWithoutGeminiStaging() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        relationship(owner, evidence(121, "Mera dost Dev hai", "Mera dost Dev hai", listOf("Dev")), "Dev", PersonRelationship.FRIEND)
        val q = evidence(122, "Ab mera dost kaun hai", "Ab mera dost kaun hai")
        val execution = LocalFastMemoryLane(owner).recall(q)
        assertNotNull(execution)
        assertEquals(MemoryRecallType.FRIENDS, execution!!.intent.type)
        assertEquals("Dev", execution.outcome.rows.single().entityName)
        assertTrue(execution.outcome.durationMs >= 0)
        assertNull(LocalMemoryRecallRouter.classify(evidence(1221, "Ab screen pe kya dikh raha hai", "Ab screen pe kya dikh raha hai")))
        assertNull(LocalMemoryRecallRouter.classify(evidence(1222, "Mere dost ko memory se hatao", "Mere dost ko memory se hatao")))
    }

    @Test fun preferenceGoalEpisodeAndTypedSemanticRecallStayIndependent() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        val pref = evidence(123, "I prefer concise responses", "I prefer concise responses")
        assertTrue(execute(owner, pref, fact("Zopy prefers concise responses", "response_length", pref)) is MemoryBrainOutcome.Mutated)
        val temp = evidence(124, "Only today use detailed responses", "Only today use detailed responses")
        assertTrue(execute(owner, temp, fact("Use detailed responses today", "response_length", temp).copy(temporalScope = MemoryTemporalScope.TEMPORARY)) is MemoryBrainOutcome.Transient)
        assertEquals(1, owner.recall("preference", type = MemoryRecallType.PREFERENCES).rows.size)
        assertTrue(store.semantic.single { it.active }.accessCount > 0)

        val goalEvidence = evidence(125, "My goal is to complete Orion", "My goal is to complete Orion", listOf("Orion"))
        val goal = MemorySemanticFrame(MemorySemanticIntent.ADD_GOAL, goal = GoalMemoryPayload("Complete Orion", null),
            sourceSpan = goalEvidence.displayText, sourceTurnId = goalEvidence.turnId, confidence = .95,
            criticalLiterals = listOf("Orion"))
        assertTrue(execute(owner, goalEvidence, goal) is MemoryBrainOutcome.Mutated)

        val episodeEvidence = evidence(126, "Today I played chess with Neel", "Today I played chess with Neel", listOf("Neel"))
        val episode = MemorySemanticFrame(MemorySemanticIntent.ADD_EPISODE,
            episode = EpisodicMemoryPayload("ACTIVITY", "Played chess with Neel", listOf("Neel")),
            temporalScope = MemoryTemporalScope.HISTORICAL, sourceSpan = episodeEvidence.displayText,
            sourceTurnId = episodeEvidence.turnId, confidence = .95, criticalLiterals = listOf("Neel"))
        assertTrue(execute(owner, episodeEvidence, episode) is MemoryBrainOutcome.Mutated)
        assertTrue(owner.recall("friends", type = MemoryRecallType.FRIENDS).rows.isEmpty())
        listOf(MemoryCategory.PROJECT, MemoryCategory.IDEA, MemoryCategory.SOLUTION, MemoryCategory.WORKFLOW).forEachIndexed { i, category ->
            val e = evidence(130L + i, "Explicit ${category.name.lowercase()} Atlas", "Explicit ${category.name.lowercase()} Atlas", listOf("Atlas"))
            assertTrue(execute(owner, e, fact("Atlas ${category.name.lowercase()}", "${category.name}:atlas", e).copy(category = category)) is MemoryBrainOutcome.Mutated)
        }
        assertEquals(4, store.semantic.count { it.category in setOf("PROJECT", "IDEA", "SOLUTION", "WORKFLOW") })
    }

    @Test fun everySurfacedTypeDeletesThroughOwnerAndRetrievalUpdatesAccessMetadata() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        val goalEvidence = evidence(140, "My goal is Nova", "My goal is Nova", listOf("Nova"))
        execute(owner, goalEvidence, MemorySemanticFrame(MemorySemanticIntent.ADD_GOAL,
            goal = GoalMemoryPayload("Nova", null), sourceSpan = goalEvidence.displayText,
            sourceTurnId = 140, confidence = .95, criticalLiterals = listOf("Nova")))
        val episodeEvidence = evidence(141, "I visited Jaipur today", "I visited Jaipur today", listOf("Jaipur"))
        execute(owner, episodeEvidence, MemorySemanticFrame(MemorySemanticIntent.ADD_EPISODE,
            episode = EpisodicMemoryPayload("TRAVEL", "Visited Jaipur", emptyList()), sourceSpan = episodeEvidence.displayText,
            sourceTurnId = 141, confidence = .95, criticalLiterals = listOf("Jaipur")))

        val goal = owner.recall("goal", type = MemoryRecallType.GOALS).rows.single()
        val episode = owner.recall("episode", type = MemoryRecallType.EPISODES).rows.single()
        assertTrue(store.goals.values.single().accessCount > 0)
        assertTrue(store.episodes.single().first.accessCount > 0)
        assertTrue(owner.deleteMemory(goal)); assertTrue(owner.deleteMemory(episode))
        assertTrue(owner.recall("goal", type = MemoryRecallType.GOALS).rows.isEmpty())
        assertTrue(owner.recall("episode", type = MemoryRecallType.EPISODES).rows.isEmpty())
        repeat(5) { owner.recordBehaviorObservation(BehaviorSignal(BehaviorObservationKind.APP_USAGE,
            "Calendar", "session-$it", it * BehaviorMemoryLearner.DAY_MS)) }
        val behavior = owner.activeCards().single { it.kind == "BEHAVIOR" }
        assertTrue(owner.deleteMemory(behavior)); assertTrue(store.behavior.isEmpty())
    }

    @Test fun wholePersonDeleteDoesNotBecomeRelationshipRemovalAndRenameKeepsId() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        relationship(owner, evidence(150, "Mera dost Kabir hai", "Mera dost Kabir hai", listOf("Kabir")), "Kabir", PersonRelationship.FRIEND)
        val id = store.peopleByName("Kabir").single().entityId
        val renameEvidence = evidence(151, "Kabir is actually Kabeer", "Kabir is actually Kabeer", listOf("Kabir", "Kabeer"))
        val rename = MemorySemanticFrame(MemorySemanticIntent.RENAME_ENTITY, person = "Kabir", replacementPerson = "Kabeer",
            sourceSpan = renameEvidence.displayText, sourceTurnId = 151, confidence = .96,
            criticalLiterals = listOf("Kabir", "Kabeer"))
        assertTrue(execute(owner, renameEvidence, rename) is MemoryBrainOutcome.Mutated)
        assertEquals(id, store.peopleByName("Kabeer").single().entityId)
        val deleteEvidence = evidence(152, "Remove Kabeer from my memory", "Remove Kabeer from my memory", listOf("Kabeer"))
        val delete = MemorySemanticFrame(MemorySemanticIntent.DELETE_ENTITY, person = "Kabeer",
            sourceSpan = deleteEvidence.displayText, sourceTurnId = 152, confidence = .99, criticalLiterals = listOf("Kabeer"))
        assertTrue(execute(owner, deleteEvidence, delete) is MemoryBrainOutcome.Deleted)
        assertTrue(store.peopleByName("Kabeer").isEmpty())
    }

    private suspend fun relationship(owner: MemoryBrainCoordinator, e: AuthoritativeMemoryTurnEvidence, name: String, type: PersonRelationship) {
        assertTrue(execute(owner, e, MemorySemanticFrame(MemorySemanticIntent.ADD_RELATIONSHIP,
            person = name, relationship = type, sourceSpan = e.displayText, sourceTurnId = e.turnId,
            confidence = .96, criticalLiterals = listOf(name))) is MemoryBrainOutcome.Mutated)
    }
    private suspend fun execute(owner: MemoryBrainCoordinator, e: AuthoritativeMemoryTurnEvidence, frame: MemorySemanticFrame): MemoryBrainOutcome {
        val plan = owner.prepareFinalTurn(e, listOf(frame.copy(sourceTurnId = e.turnId)))
        return owner.executeFinalTurnPlan(plan, e)
    }
    private fun fact(value: String, key: String, e: AuthoritativeMemoryTurnEvidence) = MemorySemanticFrame(
        MemorySemanticIntent.ADD_FACT, temporalScope = MemoryTemporalScope.CURRENT, fact = value,
        category = MemoryCategory.PREFERENCE, stableKey = key, sourceSpan = e.displayText,
        sourceTurnId = e.turnId, confidence = .96)
    private fun evidence(turn: Long, canonical: String, display: String, names: List<String> = emptyList()): AuthoritativeMemoryTurnEvidence {
        val session = "completion-${turn / 100}"
        AiriMemoryRuntime.claimTurn(session, turn)
        return AuthoritativeMemoryTurnEvidence(turn, canonical, display, names, names, session, "$session:$turn", turn)
    }
}
