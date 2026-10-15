package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenVikingMemoryDiffAuditTest {
    @Test fun durablePreferenceProducesVerifiedTypedDiff() = runBlocking {
        val store = InMemoryAiriMemoryStore()
        val owner = MemoryBrainCoordinator(store, recoverOnInit = false)
        val session = "ov-diff-preference"
        val turn = 901L
        AiriMemoryRuntime.claimTurn(session, turn)
        val evidence = AuthoritativeMemoryTurnEvidence(
            turnId = turn,
            canonicalText = "Mujhe short answers pasand hain.",
            displayText = "Mujhe short answers pasand hain.",
            sessionId = session,
            utteranceId = "$session:$turn",
            contextGeneration = turn
        )
        val frame = MemorySemanticFrame(
            intent = MemorySemanticIntent.ADD_FACT,
            temporalScope = MemoryTemporalScope.CURRENT,
            fact = "User prefers short answers",
            category = MemoryCategory.PREFERENCE,
            stableKey = "response_length",
            confidence = .98,
            sourceSpan = evidence.canonicalText,
            sourceTurnId = turn,
            sourceSessionId = session,
            assertionMode = MemoryAssertionMode.USER_ASSERTED
        )

        val plan = owner.prepareFinalTurn(evidence, listOf(frame))
        val outcome = owner.executeFinalTurnPlan(plan, evidence)
        assertTrue(outcome is MemoryBrainOutcome.Mutated)

        val diff = owner.memoryDiff(session, turn)
        assertTrue(diff.verified)
        assertEquals(1, diff.adds)
        assertEquals(MemoryOrganizationBucket.PREFERENCES, diff.items.single().bucket)
        assertEquals(MemoryCategory.PREFERENCE.name, diff.items.single().category)
        assertTrue(diff.items.single().statement!!.contains("short answers", ignoreCase = true))
    }

    @Test fun repeatedPreferenceIsJournaledAsReinforcementNotDuplicate() = runBlocking {
        val store = InMemoryAiriMemoryStore()
        val owner = MemoryBrainCoordinator(store, recoverOnInit = false)
        val session = "ov-diff-reinforce"

        suspend fun write(turn: Long) {
            AiriMemoryRuntime.claimTurn(session, turn)
            val evidence = AuthoritativeMemoryTurnEvidence(
                turn, "I prefer concise replies.", "I prefer concise replies.",
                sessionId = session, utteranceId = "$session:$turn", contextGeneration = turn
            )
            val frame = MemorySemanticFrame(
                intent = MemorySemanticIntent.ADD_FACT,
                temporalScope = MemoryTemporalScope.CURRENT,
                fact = "User prefers concise replies",
                category = MemoryCategory.COMMUNICATION_STYLE,
                stableKey = "response_length",
                confidence = .96,
                sourceSpan = evidence.canonicalText,
                sourceTurnId = turn,
                sourceSessionId = session,
                assertionMode = MemoryAssertionMode.USER_ASSERTED
            )
            val plan = owner.prepareFinalTurn(evidence, listOf(frame))
            owner.executeFinalTurnPlan(plan, evidence)
        }

        write(910L)
        write(911L)

        val diff = owner.memoryDiff(session, 911L)
        assertTrue(diff.verified)
        assertEquals(1, diff.reinforces)
        assertEquals(MemoryOrganizationBucket.PREFERENCES, diff.items.single().bucket)
        assertEquals(1, store.semantic.count { it.active && it.semanticKey == AiriText.semanticKey("response_length") })
    }

    @Test fun organizationBucketsStayTypedWithoutNewStorageOwner() {
        assertEquals(MemoryOrganizationBucket.PROFILE, MemoryOrganization.bucket(MemoryCategory.IDENTITY.name))
        assertEquals(MemoryOrganizationBucket.ENTITIES, MemoryOrganization.bucket(MemoryCategory.PERSON.name))
        assertEquals(MemoryOrganizationBucket.EVENTS, MemoryOrganization.bucket(MemoryCategory.LIFE_EVENT.name))
        assertEquals(MemoryOrganizationBucket.EXPERIENCES, MemoryOrganization.bucket(MemoryCategory.WORKFLOW.name))
        assertEquals(MemoryOrganizationBucket.PROJECTS, MemoryOrganization.bucket(MemoryCategory.PROJECT.name))
    }
}
