package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticMemoryExtractorTest {
    @Test fun learnsClearEnglishPreference() {
        val candidate = AutomaticMemoryExtractor.extract("I love horror movies")
        assertEquals(MemoryCategory.PREFERENCE, candidate?.category)
        assertEquals("Zopy likes horror movies", candidate?.fact)
        assertEquals("preference:likes:horror movies", candidate?.stableKey)
        assertEquals(MemorySensitivity.LOW, candidate?.sensitivity)
        assertTrue(candidate?.explicitlyRequested == false)
    }

    @Test fun learnsClearRomanHinglishPreference() {
        val candidate = AutomaticMemoryExtractor.extract(
            "mujhe horror movies bohot pasand hain"
        )
        assertEquals("Zopy likes horror movies", candidate?.fact)
        assertEquals("automatic_conversation", candidate?.source)
    }

    @Test fun favoriteCategoryUsesReplaceableStableKey() {
        val candidate = AutomaticMemoryExtractor.extract("mera favorite game hai BGMI")
        assertEquals("Zopy's favorite game is BGMI", candidate?.fact)
        assertEquals("preference:favorite:game", candidate?.stableKey)
    }

    @Test fun rejectsAmbiguousPronounPreference() {
        assertNull(AutomaticMemoryExtractor.extract("mujhe woh pasand hai"))
        assertNull(AutomaticMemoryExtractor.extract("I like that"))
    }

    @Test fun rejectsNegativePersonalAndSecretStatements() {
        assertNull(AutomaticMemoryExtractor.extract("I don't like horror movies"))
        assertNull(AutomaticMemoryExtractor.extract("mujhe meri dost pasand hai"))
        assertNull(AutomaticMemoryExtractor.extract("I like password secret123"))
        assertNull(AutomaticMemoryExtractor.extract("mujhe health advice pasand hai"))
        assertNull(AutomaticMemoryExtractor.extract("mujhe na ghumana pasand hai"))
    }

    @Test fun ignoresOrdinaryConversationAndCommands() {
        assertNull(AutomaticMemoryExtractor.extract("time"))
        assertNull(AutomaticMemoryExtractor.extract("WhatsApp message aaya hai kya"))
        assertNull(AutomaticMemoryExtractor.extract("meri dost reply late karti hai"))
    }

    @Test fun normalizesObservedRomanizedMovieTranscripts() {
        assertEquals(
            "Zopy likes science-fiction movies",
            AutomaticMemoryExtractor.extract(
                "mujhe sainsa sainsa phiksana muvi bahuta pasanda hai"
            )?.fact
        )
        assertEquals(
            "Zopy likes horror movies",
            AutomaticMemoryExtractor.extract(
                "mujhe horara muvi bahuta pasanda hai"
            )?.fact
        )
    }

    @Test fun canonicalizesObservedGhumnaTranscriptBeforeSaving() {
        assertEquals(
            "Zopy likes ghumna",
            AutomaticMemoryExtractor.extract("mujhe ghumana pasand hai")?.fact
        )
    }
}
