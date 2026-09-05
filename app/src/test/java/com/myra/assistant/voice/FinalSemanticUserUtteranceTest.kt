package com.myra.assistant.voice

import com.myra.assistant.data.memory.BestFriendNameCorrection
import com.myra.assistant.data.memory.BestFriendNameCorrectionParser
import com.myra.assistant.data.memory.MemoryRelationshipPolicy
import com.myra.assistant.data.memory.PersonLinkedMemoryExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalSemanticUserUtteranceTest {
    @Test fun kareemIsIdenticalForDisplayAndEverySemanticConsumer() {
        val utterance = utterance("मेरा बेस्ट फ्रेंड करीम है।")
        assertEquals("मेरा बेस्ट फ्रेंड करीम है।", utterance.canonicalSemanticText)
        assertEquals("Mera best friend Kareem hai.", utterance.displayText)
        assertEquals(utterance.displayText, utterance.memoryExtractorInput)
        assertEquals(utterance.displayText, utterance.correctionParserInput)
        assertEquals(utterance.displayText, utterance.deleteParserInput)
        assertEquals(utterance.displayText, utterance.clarificationResolverInput)
        assertEquals(listOf("Kareem"), utterance.canonicalNameTokens)
        assertTrue(utterance.semanticConsistency)
        val candidate = PersonLinkedMemoryExtractor.extractAll(utterance.memoryExtractorInput).first()
        assertEquals("Kareem", MemoryRelationshipPolicy.personName(candidate.fact))
    }

    @Test fun karimaRemainsASeparateStoredIdentity() {
        val kareem = utterance("मेरा बेस्ट फ्रेंड करीम है।")
        val karima = utterance("मेरा बेस्ट फ्रेंड करीमा है।")
        assertEquals("मेरा बेस्ट फ्रेंड करीमा है।", karima.canonicalSemanticText)
        assertEquals("Mera best friend Karima hai.", karima.displayText)
        assertFalse(kareem.canonicalNameTokens == karima.canonicalNameTokens)
        val candidate = PersonLinkedMemoryExtractor.extractAll(karima.memoryExtractorInput).first()
        assertEquals("Karima", MemoryRelationshipPolicy.personName(candidate.fact))
    }

    @Test fun bothHindiCorrectionDirectionsPreserveNameContrast() {
        assertEquals(
            BestFriendNameCorrection("Kareem", "Karima"),
            BestFriendNameCorrectionParser.parse(
                utterance("करीम नहीं करीमा").correctionParserInput,
                "Kareem"
            )
        )
        assertEquals(
            BestFriendNameCorrection("Karima", "Kareem"),
            BestFriendNameCorrectionParser.parse(
                utterance("करीमा नहीं करीम").correctionParserInput,
                "Karima"
            )
        )
    }

    @Test fun unknownOldHindiNameCannotCorruptProtectedKareemToken() {
        val utterance = utterance("हरीमा नहीं करीम")
        assertEquals("हरीमा नहीं करीम", utterance.canonicalSemanticText)
        assertEquals("Harima nahi Kareem", utterance.displayText)
        assertEquals(
            BestFriendNameCorrection("Harima", "Kareem"),
            BestFriendNameCorrectionParser.parse(utterance.correctionParserInput, null)
        )
    }

    @Test fun grammaticalKarimaFragmentCannotCorrectOrReplaceKareem() {
        val utterance = utterance("करीमा ने मुझे कॉल किया।")
        assertNull(BestFriendNameCorrectionParser.parse(utterance.correctionParserInput, "Kareem"))
    }

    @Test fun mismatchedNameTokensFailSemanticConsistencyGate() {
        val inconsistent = FinalSemanticUserUtterance(
            sessionId = "session",
            turnId = 2L,
            utteranceId = "session:2",
            rawGeminiTranscript = "करीम",
            canonicalSemanticText = "Kareem",
            displayText = "Karima",
            canonicalNameTokens = listOf("Kareem"),
            displayNameTokens = listOf("Karima")
        )
        assertFalse(inconsistent.semanticConsistency)
    }

    @Test fun displayTransliterationCannotCorruptSemanticIntentText() {
        val formatted = FinalTranscriptDisplayFormatter.Result(
            transliterated = "Nice jao.", display = "Nice jao.",
            latinWordsPreserved = false, properNameProtected = false,
            protectedNameTokens = emptyList(), appliedRuleIds = emptyList()
        )
        val utterance = FinalSemanticUserUtterance.from("session", 3L, "नीचे जाओ।", formatted)
        assertEquals("नीचे जाओ।", utterance.canonicalSemanticText)
        assertEquals("Nice jao.", utterance.displayText)
    }

    private fun utterance(raw: String): FinalSemanticUserUtterance {
        val formatted = FinalTranscriptDisplayFormatter.format(raw) { literalIcu[it] ?: it }
        return FinalSemanticUserUtterance.from("session", 1L, raw, formatted)
    }

    private companion object {
        val literalIcu = mapOf(
            "मेरा" to "mērā", "बेस्ट" to "bēsṭa", "फ्रेंड" to "phrēṇḍa",
            "करीम" to "karīma", "करीमा" to "karīmā", "है" to "hai",
            "हरीमा" to "harīmā",
            "नहीं" to "nahīṁ", "ने" to "nē", "मुझे" to "mujhe",
            "कॉल" to "kŏla", "किया" to "kiyā"
        )
    }
}
