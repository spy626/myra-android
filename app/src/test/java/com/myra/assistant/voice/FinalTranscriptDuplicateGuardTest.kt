package com.myra.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalTranscriptDuplicateGuardTest {
    @Test fun collapsesExactDoubledRomanSentence() {
        val result = FinalTranscriptDuplicateGuard.collapse(
            "Mera favourite game kya hai?Mera favourite game kya hai?"
        )
        assertTrue(result.collapseApplied)
        assertEquals("Mera favourite game kya hai?", result.text)
    }

    @Test fun collapsesExactDoubledHindiSentence() {
        val result = FinalTranscriptDuplicateGuard.collapse("लेयर सुन रहे?लेयर सुन रहे?")
        assertTrue(result.collapseApplied)
        assertEquals("लेयर सुन रहे?", result.text)
    }

    @Test fun preservesLegitimateWordRepetition() {
        assertFalse(FinalTranscriptDuplicateGuard.collapse("Nahi nahi, ruk jao.").collapseApplied)
        assertFalse(FinalTranscriptDuplicateGuard.collapse("Bahut bahut shukriya.").collapseApplied)
    }

    @Test fun preservesTwoDifferentSentences() {
        val text = "Mera favourite game kya hai? Ab screen par kya hai?"
        assertEquals(text, FinalTranscriptDuplicateGuard.collapse(text).text)
    }
}
