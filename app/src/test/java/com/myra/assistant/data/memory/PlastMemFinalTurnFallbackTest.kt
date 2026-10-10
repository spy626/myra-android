package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlastMemFinalTurnFallbackTest {
    @Test fun missingLiveToolFallsBackToGroundedFinalTurnSemanticConsolidation() = runBlocking {
        val store = InMemoryAiriMemoryStore()
        val provider = object : MemoryReasoningProvider by UnavailableMemoryReasoningProvider {
            override suspend fun interpretFinalTurn(
                evidence: AuthoritativeMemoryTurnEvidence
            ) = FinalTurnSemanticInterpretation(
                displayText = "Mujhe short answers pasand hain.",
                operations = listOf(
                    MemorySemanticFrame(
                        intent = MemorySemanticIntent.ADD_FACT,
                        temporalScope = MemoryTemporalScope.CURRENT,
                        fact = "User prefers short answers",
                        category = MemoryCategory.PREFERENCE,
                        stableKey = "response_length",
                        confidence = .97,
                        sourceSpan = evidence.canonicalText,
                        sourceTurnId = evidence.turnId,
                        sourceSessionId = evidence.sessionId,
                        assertionMode = MemoryAssertionMode.USER_ASSERTED
                    )
                )
            )
        }
        val owner = MemoryBrainCoordinator(store, provider, recoverOnInit = false)
        val session = "plast-final-turn"
        val turn = 701L
        AiriMemoryRuntime.claimTurn(session, turn)
        val evidence = AuthoritativeMemoryTurnEvidence(
            turn, "Mujhe sorta ansara pasanda hai.", "Mujhe sorta ansara pasanda hai.",
            sessionId = session, utteranceId = "$session:$turn", contextGeneration = turn
        )

        val plan = owner.prepareFinalTurn(evidence, emptyList())
        assertEquals(MemoryDecision.SAVE, plan.decision)
        assertEquals("Mujhe short answers pasand hain.", plan.displayProjection)
        val outcome = owner.executeFinalTurnPlan(plan, evidence)
        assertTrue(outcome is MemoryBrainOutcome.Mutated)
        assertTrue(owner.recall("", type = MemoryRecallType.PREFERENCES).rows.any {
            it.fact.contains("short answers", ignoreCase = true)
        })
    }

    @Test fun candidateGateDoesNotTreatQuestionsAsDurableStatements() {
        val statement = AuthoritativeMemoryTurnEvidence(
            1, "Mujhe short answers pasand hain", "Mujhe short answers pasand hain"
        )
        val question = AuthoritativeMemoryTurnEvidence(
            2, "Mujhe kya pasand hai", "Mujhe kya pasand hai"
        )
        assertTrue(FinalTurnSemanticCandidateGate.shouldInterpret(statement))
        assertTrue(!FinalTurnSemanticCandidateGate.shouldInterpret(question))
    }

    @Test fun displayProjectionRejectsChangedNumbers() {
        val evidence = AuthoritativeMemoryTurnEvidence(
            3, "Mera budget 2000 hai", "Mera budget 2000 hai"
        )
        assertEquals(null, FinalTurnDisplayProjectionPolicy.select(evidence, "Mera budget 5000 hai"))
    }
}
