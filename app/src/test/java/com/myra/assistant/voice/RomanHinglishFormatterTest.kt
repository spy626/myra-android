package com.myra.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class RomanHinglishFormatterTest {
    @Test fun cleansObservedLiteralHindiTransliteration() {
        assertEquals(
            "Mera ek male best friend hai. Uska naam karima hai.",
            RomanHinglishFormatter.format(
                "mera eka mela besta phrenda hai. usaka nama karima hai."
            )
        )
    }

    @Test fun preservesIndicPronunciationLongEnoughToSpellKareem() {
        assertEquals(
            "Mera ek male best friend hai. Uska naam Kareem hai.",
            RomanHinglishFormatter.format(
                "mērā ēka mēla bēsṭa phrēṇḍa hai. usakā nāma karīma hai."
            )
        )
    }

    @Test fun doesNotRewriteAPlainKarimaNameAsKareem() {
        assertEquals(
            "Uska naam Karima hai.",
            RomanHinglishFormatter.format("usaka nama Karima hai.")
        )
    }

    @Test fun cleansObservedAgeAndPreferenceTranscripts() {
        assertEquals(
            "Main 26 saal ka hoon.",
            RomanHinglishFormatter.format("maim 26 sala ka hum.")
        )
        assertEquals(
            "Mujhe ghumna pasand hai.",
            RomanHinglishFormatter.format("mujhe ghumana pasanda hai.")
        )
        assertEquals(
            "Mujhe ghumna pasand hai.",
            RomanHinglishFormatter.format("mujhe ghūmanā pasandā hai.")
        )
    }

    @Test fun cleansObservedConversationAndMemoryCommandCorruption() {
        assertEquals("Kya kar rahe ho?", RomanHinglishFormatter.format("Kya kara rahe?"))
        assertEquals("Nahi.", RomanHinglishFormatter.format("Nahim."))
        assertEquals("Ghumna memory se delete kar do.", RomanHinglishFormatter.format("Gomna memory se delete kar do."))
        assertEquals("Ghumna memory se delete kar do.", RomanHinglishFormatter.format("Go and memory setting kar do."))
        assertEquals(
            "Voice input unclear - please repeat.",
            RomanHinglishFormatter.format("Goom naam hai main re-visit karta hun.")
        )
    }

    @Test fun cleansObservedMunnarSentenceWithoutRewritingItsMeaning() {
        assertEquals(
            "Munnar bahut jagah ja ke aaya hoon.",
            RomanHinglishFormatter.format("Munara bahuta jagaha ja ke aya hum.")
        )
    }
}
