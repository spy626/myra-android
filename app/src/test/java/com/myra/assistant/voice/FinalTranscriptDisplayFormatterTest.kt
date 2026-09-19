package com.myra.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalTranscriptDisplayFormatterTest {
    private fun display(raw: String): String =
        FinalTranscriptDisplayFormatter.format(raw) { token -> literalIcu[token] ?: token }.display

    @Test fun formatsNaturalHinglishConversation() {
        assertEquals(
            "Ab mujhe batao, aaj hum kis baare mein baat karein?",
            display("अब मुझे बताओ आज हम किस बारे में बात करें?")
        )
        assertEquals("Ruko, meri baat suno.", display("रुको, मेरी बात सुनो।"))
    }

    @Test fun preservesDistinctNamesWithoutMemoryFuzzyMatching() {
        assertEquals("Mera best friend Karima hai.", display("मेरा बेस्ट फ्रेंड करीमा है।"))
        assertEquals("Karima nahi Kareem", display("करीमा नहीं करीम"))
        assertFalse(display("करीमा") == display("करीम"))
    }

    @Test fun avoidsObservedLiteralIcuSchwaForms() {
        assertEquals("Mere baare mein kya jaante ho?", display("मेरे बारे में क्या जानते हो?"))
        assertEquals("Mera dost kaun hai?", display("मेरा दोस्त कौन है?"))
    }

    @Test fun preservesExistingLatinWordsExactly() {
        val result = FinalTranscriptDisplayFormatter.format(
            "मेरा best friend gaming channel coding BGMI है।"
        ) { token -> literalIcu[token] ?: token }
        assertEquals("Mera best friend gaming channel coding BGMI hai.", result.display)
        assertTrue(result.latinWordsPreserved)
    }

    @Test fun reportsProtectedNameAndConservativeRules() {
        val result = FinalTranscriptDisplayFormatter.format("करीमा नहीं करीम") { literalIcu[it] ?: it }
        assertTrue(result.properNameProtected)
        assertTrue("protected_hindi_name" in result.appliedRuleIds)
        assertTrue("exact_hindi_readability" in result.appliedRuleIds)
    }

    private companion object {
        // Representative Android ICU Any-Latin output. The formatter must not expose
        // these literal final-schwa forms in the user bubble.
        val literalIcu = mapOf(
            "अब" to "aba", "मुझे" to "mujhe", "बताओ" to "batā'o", "आज" to "āja",
            "हम" to "hama", "किस" to "kisa", "बारे" to "bārē", "में" to "mēṁ",
            "बात" to "bāta", "करें" to "karēṁ", "रुको" to "rukō", "मेरी" to "mērī",
            "सुनो" to "sunō", "मेरा" to "mērā", "मेरे" to "mērē", "क्या" to "kyā",
            "जानते" to "jānatē", "हो" to "hō", "दोस्त" to "dōsta", "कौन" to "kauna",
            "करीमा" to "karīmā", "करीम" to "karīma"
        )
    }
}
