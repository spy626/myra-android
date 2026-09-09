package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StructuredGenericMemoryPlanTest {
    @Test fun genericPreferenceAddUsesCoordinatorPlan() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val text = "Mujhe short answers pasand hain"
        val plan = brain.prepareFinalTurn(text, listOf(generic(
            MemorySemanticIntent.ADD_FACT, MemoryCategory.PREFERENCE,
            "Zopy prefers short answers", "response_style", text
        )))

        assertEquals(MemoryDecision.SAVE, plan.decision)
        assertTrue(brain.executeFinalTurnPlan(plan) is MemoryBrainOutcome.Mutated)
        assertEquals(PreferenceMemoryIdentity.RESPONSE_VERBOSITY_KEY, repository.allActive().single().stableKey)
        val recalled = brain.recall("answer preference", type = MemoryRecallType.GENERAL)
        assertTrue(recalled.rows.single().fact.contains("short"))
    }

    @Test fun friendshipRecallKeepsFriendFamilyDistinctFromBestFriend() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        repository.addPersonRelationship("Dev", PersonRelationship.FRIEND)
        repository.addPersonRelationship("Mira", PersonRelationship.GOOD_FRIEND)

        val friends = brain.recall("friends", type = MemoryRecallType.FRIENDS)
        assertEquals(setOf("Dev", "Mira"), friends.rows.mapNotNull { it.entityName }.toSet())
        val bestFriends = brain.recall("best friend", type = MemoryRecallType.BEST_FRIEND)
        assertTrue(bestFriends.rows.isEmpty())
    }

    @Test fun lastTransactionRecallUsesVerifiedWorkingContextWithoutMutation() = runBlocking {
        MemoryWorkingContext.clear()
        MemoryWorkingContext.transaction(LastMemoryTransaction(
            type = MemoryDecision.UPDATE,
            oldValue = "old value",
            newValue = "new value",
            status = MemoryTransactionStatus.FAILED,
            failureReason = "ambiguous"
        ))
        val repository = MemoryRepository(FakeMemoryDao())
        val outcome = MemoryBrainCoordinator(repository).recall(
            "last update", type = MemoryRecallType.LAST_TRANSACTION
        )
        assertNotNull(outcome.workingAnswer)
        assertEquals(1, outcome.rows.size)
        assertTrue(repository.allActive().isEmpty())
        MemoryWorkingContext.clear()
    }

    @Test fun semanticUpdateSupersedesOnlySamePreferenceDimension() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val first = "Mujhe short answers pasand hain"
        brain.executeFinalTurnPlan(brain.prepareFinalTurn(first, listOf(generic(
            MemorySemanticIntent.ADD_FACT, MemoryCategory.COMMUNICATION_STYLE,
            "Zopy prefers short answers", "answer_length", first
        ))))
        repository.saveGrounded(MemoryCandidate(
            MemoryCategory.PREFERENCE, "Zopy likes horror movies", "semantic:preference:movie_genre",
            MemorySensitivity.LOW, .95, provenance = MemoryProvenance.GEMINI_GROUNDED_PROPOSAL
        ))

        val updateText = "Actually ab mujhe detailed answers pasand hain"
        val outcome = brain.executeFinalTurnPlan(brain.prepareFinalTurn(updateText, listOf(generic(
            MemorySemanticIntent.SUPERSEDE_FACT, MemoryCategory.PREFERENCE,
            "Zopy prefers detailed answers", "response_style", updateText
        ))))

        assertTrue(outcome is MemoryBrainOutcome.Mutated)
        val active = repository.allActive()
        assertEquals(1, active.count(PreferenceMemoryIdentity::isResponseVerbosity))
        assertTrue(active.single(PreferenceMemoryIdentity::isResponseVerbosity).fact.contains("detailed"))
        assertTrue(active.any { it.fact.contains("horror") })
    }

    @Test fun unrelatedPreferenceProjectGoalHabitAndWorkflowCanUseAddFact() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val values = listOf(
            generic(MemorySemanticIntent.ADD_FACT, MemoryCategory.PREFERENCE, "Zopy likes horror movies", "movie_genre", "Mujhe horror movies pasand hain"),
            generic(MemorySemanticIntent.ADD_FACT, MemoryCategory.PROJECT, "Zopy builds Aurora assistant", "current_project", "I am building Aurora assistant"),
            generic(MemorySemanticIntent.ADD_FACT, MemoryCategory.GOAL, "Zopy plans to finish Atlas", "current_goal", "My plan is to finish Atlas"),
            generic(MemorySemanticIntent.ADD_FACT, MemoryCategory.HABIT, "Zopy codes daily", "coding_frequency", "I code daily"),
            generic(MemorySemanticIntent.ADD_FACT, MemoryCategory.WORKFLOW, "Zopy reviews tests before release", "release_checks", "I review tests before release")
        )
        for (frame in values) {
            val plan = brain.prepareFinalTurn(frame.evidence, listOf(frame))
            assertTrue(brain.executeFinalTurnPlan(plan) is MemoryBrainOutcome.Mutated)
        }
        assertEquals(5, repository.allActive().size)
    }

    @Test fun secretGenericProposalIsRejectedBeforeRoomMutation() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val text = "My OTP is 123456"
        val plan = brain.prepareFinalTurn(text, listOf(generic(
            MemorySemanticIntent.ADD_FACT, MemoryCategory.IDENTITY, text, "login_code", text
        )))
        assertEquals(MemoryDecision.REJECT, plan.decision)
        assertTrue(brain.executeFinalTurnPlan(plan) is MemoryBrainOutcome.Rejected)
        assertTrue(repository.allActive().isEmpty())
    }

    @Test fun ambiguousUpdateWithoutExactTargetClarifies() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        repository.saveGrounded(MemoryCandidate(
            MemoryCategory.PROJECT, "Zopy builds Aurora", "semantic:project:aurora",
            MemorySensitivity.PERSONAL, .95
        ))
        val text = "The project is now Atlas"
        val plan = brain.prepareFinalTurn(text, listOf(generic(
            MemorySemanticIntent.UPDATE_FACT, MemoryCategory.PROJECT,
            "Zopy builds Atlas", "unknown_project", text
        )))
        assertTrue(plan.requiresClarification)
        assertEquals(1, repository.allActive().size)
    }

    @Test fun exactTargetUpdateSupersedesOnlyThatDimension() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val originalText = "I am building Aurora assistant"
        brain.executeFinalTurnPlan(brain.prepareFinalTurn(originalText, listOf(generic(
            MemorySemanticIntent.ADD_FACT, MemoryCategory.PROJECT,
            "Zopy builds Aurora assistant", "current_project", originalText
        ))))
        repository.saveGrounded(MemoryCandidate(
            MemoryCategory.GOAL, "Zopy plans Atlas", "semantic:goal:current_goal",
            MemorySensitivity.PERSONAL, .95
        ))

        val updateText = "My project is now Borealis assistant"
        val outcome = brain.executeFinalTurnPlan(brain.prepareFinalTurn(updateText, listOf(generic(
            MemorySemanticIntent.UPDATE_FACT, MemoryCategory.PROJECT,
            "Zopy builds Borealis assistant", "current_project", updateText
        ))))

        assertTrue(outcome is MemoryBrainOutcome.Mutated)
        val active = repository.allActive()
        assertEquals(1, active.count { it.stableKey == "semantic:project:current_project" })
        assertTrue(active.single { it.stableKey == "semantic:project:current_project" }.fact.contains("Borealis"))
        assertTrue(active.any { it.stableKey == "semantic:goal:current_goal" })
    }

    @Test fun temporaryAndHistoricalCorrectionsCannotReplaceCurrentPreference() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val current = "Mujhe short answers pasand hain"
        brain.executeFinalTurnPlan(brain.prepareFinalTurn(current, listOf(generic(
            MemorySemanticIntent.ADD_FACT, MemoryCategory.PREFERENCE,
            "Zopy prefers short answers", "response_style", current
        ))))

        val temporary = "Right now I prefer detailed answers"
        val temporaryPlan = brain.prepareFinalTurn(temporary, listOf(generic(
            MemorySemanticIntent.SUPERSEDE_FACT, MemoryCategory.PREFERENCE,
            "Zopy prefers detailed answers", "response_style", temporary,
            MemoryTemporalScope.TEMPORARY
        )))
        assertEquals(MemoryDecision.REJECT, temporaryPlan.decision)

        val historical = "Earlier I preferred detailed answers"
        val historicalPlan = brain.prepareFinalTurn(historical, listOf(generic(
            MemorySemanticIntent.UPDATE_FACT, MemoryCategory.PREFERENCE,
            "Zopy prefers detailed answers", "response_style", historical,
            MemoryTemporalScope.HISTORICAL
        )))
        assertEquals(MemoryDecision.REJECT, historicalPlan.decision)

        val active = repository.allActive()
        assertEquals(1, active.count(PreferenceMemoryIdentity::isResponseVerbosity))
        assertTrue(active.single(PreferenceMemoryIdentity::isResponseVerbosity).fact.contains("short"))
    }

    @Test fun currentCorrectionStillSupersedesAndRecurringHabitCanPersist() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val short = "Mujhe short answers pasand hain"
        brain.executeFinalTurnPlan(brain.prepareFinalTurn(short, listOf(generic(
            MemorySemanticIntent.ADD_FACT, MemoryCategory.PREFERENCE,
            "Zopy prefers short answers", "response_style", short
        ))))
        val detailed = "Actually ab mujhe detailed answers pasand hain"
        brain.executeFinalTurnPlan(brain.prepareFinalTurn(detailed, listOf(generic(
            MemorySemanticIntent.SUPERSEDE_FACT, MemoryCategory.PREFERENCE,
            "Zopy prefers detailed answers", "response_style", detailed,
            MemoryTemporalScope.CURRENT
        ))))
        val recurring = "I code daily"
        brain.executeFinalTurnPlan(brain.prepareFinalTurn(recurring, listOf(generic(
            MemorySemanticIntent.ADD_FACT, MemoryCategory.HABIT,
            "Zopy codes daily", "coding_frequency", recurring,
            MemoryTemporalScope.RECURRING
        ))))

        val active = repository.allActive()
        assertEquals(1, active.count(PreferenceMemoryIdentity::isResponseVerbosity))
        assertTrue(active.single(PreferenceMemoryIdentity::isResponseVerbosity).fact.contains("detailed"))
        assertTrue(active.any { it.category == MemoryCategory.HABIT.name })
    }

    @Test fun temporaryAddFactIsNotPromoted() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val text = "Right now I prefer quiet music"
        val plan = brain.prepareFinalTurn(text, listOf(generic(
            MemorySemanticIntent.ADD_FACT, MemoryCategory.PREFERENCE,
            "Zopy prefers quiet music", "music_mood", text,
            MemoryTemporalScope.TEMPORARY
        )))
        assertEquals(MemoryDecision.REJECT, plan.decision)
        assertTrue(repository.allActive().isEmpty())
    }

    @Test fun naturalRelationshipNegationIsNotAnExplicitCommand() {
        assertNull(MemoryCommandParser.parse("Dev mera friend nahi raha"))
        assertTrue(MemoryCommandParser.looksLikeIntent("Dev mera friend nahi raha"))
    }

    @Test fun repeatedToolProposalsMergeDedupeAndStayBounded() {
        val first = generic(MemorySemanticIntent.ADD_FACT, MemoryCategory.GOAL,
            "Zopy plans Atlas", "goal_atlas", "I plan Atlas")
        val second = generic(MemorySemanticIntent.ADD_FACT, MemoryCategory.HABIT,
            "Zopy codes daily", "daily_code", "I code daily")
        val merged = StagedMemoryProposalPolicy.merge(listOf(first), listOf(first, second))
        assertEquals(listOf(first, second), merged)
        assertEquals(4, StagedMemoryProposalPolicy.merge(emptyList(), List(6) {
            second.copy(stableKey = "daily_$it", fact = "fact $it")
        }).size)
    }

    @Test fun explicitRememberProducesCommandPlanAndIgnoresModelOperation() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val brain = MemoryBrainCoordinator(repository)
        val text = "Yaad rakho mujhe astronomy pasand hai"
        val plan = brain.prepareFinalTurn(text, listOf(generic(
            MemorySemanticIntent.ADD_FACT, MemoryCategory.PREFERENCE,
            "Zopy likes astronomy", "astronomy", text
        )))
        assertNotNull(plan.explicitCommand)
        assertTrue(brain.executeFinalTurnPlan(plan) is MemoryBrainOutcome.Mutated)
        assertEquals(1, repository.allActive().size)
    }

    private fun generic(
        intent: MemorySemanticIntent,
        category: MemoryCategory,
        fact: String,
        key: String,
        evidence: String,
        temporal: MemoryTemporalScope = MemoryTemporalScope.CURRENT
    ) = MemorySemanticFrame(
        intent = intent, category = category, fact = fact, stableKey = key,
        temporalScope = temporal, confidence = .96, evidence = evidence
    )
}
