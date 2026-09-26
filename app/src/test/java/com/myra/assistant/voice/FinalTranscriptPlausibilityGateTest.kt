package com.myra.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalTranscriptPlausibilityGateTest {
    @Test fun acceptsExpectedHindiHinglishAndEnglishScripts() {
        val gate = FinalTranscriptPlausibilityGate()
        assertTrue(gate.assessFinal("मेरा बेस्ट फ्रेंड करीम है।").semanticProcessingAllowed)
        assertTrue(gate.assessFinal("Mera best friend Kareem hai.").semanticProcessingAllowed)
        assertTrue(gate.assessFinal("Who is my best friend?").semanticProcessingAllowed)
    }

    @Test fun isolatedHangulFinalIsSuspiciousDuringHinglishSession() {
        val gate = FinalTranscriptPlausibilityGate()
        gate.assessFinal("Mera best friend Kareem hai.")
        val decision = gate.assessFinal("응. 메라 베스트 프렌드 코네?")
        assertEquals(TranscriptScript.HANGUL, decision.dominantScript)
        assertEquals(TranscriptPlausibility.SUSPICIOUS, decision.transcriptPlausibility)
        assertEquals("HINDI_HINGLISH", decision.recentSessionLanguageProfile)
        assertEquals("unexpected_dominant_script", decision.anomalyReason)
        assertFalse(decision.semanticProcessingAllowed)
        assertFalse(decision.userBubbleCommitAllowed)
        assertFalse(decision.memoryMutationAllowed)
    }

    @Test fun suspiciousFinalUsesClarificationWithoutGuessingARewrite() {
        assertEquals(
            "Sorry, woh clear nahi suna. Ek baar phir bolo.",
            FinalTranscriptPlausibilityGate.CLARIFICATION_REPLY
        )
        assertFalse(FinalTranscriptPlausibilityGate.CLARIFICATION_REPLY.contains("best friend"))
    }

    @Test fun suspiciousFinalCannotReachAnySemanticMutationConsumer() {
        val decision = FinalTranscriptPlausibilityGate()
            .assessFinal("응. 메라 베스트 프렌드 코네?")
        var memoryExtractionCalls = 0
        var correctionCalls = 0
        var deleteCalls = 0
        if (decision.semanticProcessingAllowed) {
            memoryExtractionCalls++
            correctionCalls++
            deleteCalls++
        }
        assertEquals(0, memoryExtractionCalls)
        assertEquals(0, correctionCalls)
        assertEquals(0, deleteCalls)
    }

    @Test fun previewDoesNotChangeSessionButRepeatedFinalsCanEstablishLanguageSwitch() {
        val gate = FinalTranscriptPlausibilityGate()
        repeat(5) { assertFalse(gate.preview("한국어 문장").semanticProcessingAllowed) }
        assertFalse(gate.assessFinal("한국어 문장 하나").semanticProcessingAllowed)
        assertFalse(gate.assessFinal("한국어 문장 둘").semanticProcessingAllowed)
        val switched = gate.assessFinal("한국어 문장 셋")
        assertTrue(switched.semanticProcessingAllowed)
        assertEquals("HANGUL", switched.recentSessionLanguageProfile)
    }
}
