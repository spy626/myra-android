package com.myra.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalMemoryProposalContractTest {
    @Test fun noisyAsrPreferenceCanPersistWhenSemanticProposalUsesVerbatimEvidence() = runBlocking {
        val store = InMemoryAiriMemoryStore()
        val owner = MemoryBrainCoordinator(store, recoverOnInit = false)
        val session = "noisy-asr-preference"
        val turn = 701L
        AiriMemoryRuntime.claimTurn(session, turn)
        val evidence = AuthoritativeMemoryTurnEvidence(
            turnId = turn,
            canonicalText = "Mujhe sorta ansara pasand hai.",
            displayText = "Mujhe sorta ansara pasand hai.",
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
            confidence = .96,
            sourceSpan = evidence.displayText,
            sourceTurnId = turn,
            assertionMode = MemoryAssertionMode.USER_ASSERTED
        )

        val plan = owner.prepareFinalTurn(evidence, listOf(frame))
        assertEquals(MemoryDecision.SAVE, plan.decision)
        val outcome = owner.executeFinalTurnPlan(plan, evidence)
        assertTrue(outcome is MemoryBrainOutcome.Mutated)
        assertTrue((outcome as MemoryBrainOutcome.Mutated).result is MemoryWriteResult.Saved)

        val recalled = owner.recall("", type = MemoryRecallType.PREFERENCES).rows
        assertTrue(recalled.any { it.fact.contains("short answers", ignoreCase = true) })
    }

    @Test fun handoffContractRequiresProposalBeforeAcknowledgingClearDurableMeaning() {
        assertTrue(MemoryProposalUsagePolicy.SYSTEM_REQUIREMENT.contains("MUST call propose_user_memory"))
        assertTrue(MemoryProposalUsagePolicy.SYSTEM_REQUIREMENT.contains("ASR or transliteration spelling is noisy"))
        assertTrue(MemoryProposalUsagePolicy.TOOL_DESCRIPTION.contains("before speaking"))
        assertTrue(MemoryProposalUsagePolicy.SOURCE_SPAN_DESCRIPTION.contains("near-verbatim"))
        assertTrue(MemoryProposalUsagePolicy.SOURCE_SPAN_DESCRIPTION.contains("Preserve ASR/transliteration spelling"))
    }
}
