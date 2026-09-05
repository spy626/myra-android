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

    @Test fun responseStyleUsesOneReplaceableStableKey() {
        val short = AutomaticMemoryExtractor.extract("I always prefer short answers")
        val detailed = AutomaticMemoryExtractor.extract("I prefer detailed responses")

        assertEquals("preference:response_style", short?.stableKey)
        assertEquals("Zopy prefers short answers", short?.fact)
        assertEquals("preference:response_style", detailed?.stableKey)
    }

    @Test fun learnsCommunicationStyleWithoutRememberCommand() {
        val style = AutomaticMemoryExtractor.extract("Give me simple answers")
        val language = AutomaticMemoryExtractor.extract("I prefer answers in Hinglish")

        assertEquals(MemoryCategory.COMMUNICATION_STYLE, style?.category)
        assertEquals("preference:response_style", style?.stableKey)
        assertEquals("Zopy prefers simple answers", style?.fact)
        assertEquals("communication:language", language?.stableKey)
    }

    @Test fun responseVerbosityPhrasesShareOneSemanticDimension() {
        listOf(
            "Give me short answers",
            "Keep answers brief",
            "Be concise",
            "Actually give me detailed answers",
            "Give longer explanations",
            "Explain things in detail"
        ).forEach { phrase ->
            val change = AutomaticMemoryChangeParser.parse(phrase) as AutomaticMemoryChange.Save
            assertEquals(phrase, "preference:response_style", change.candidate.stableKey)
        }
    }

    @Test fun learnsRecurringAppUsageAndWorkflow() {
        val app = AutomaticMemoryExtractor.extract("I usually use Chrome for reading articles")
        val workflow = AutomaticMemoryExtractor.extract("I always test on my Android phone for app releases")

        assertEquals(MemoryCategory.APP_USAGE, app?.category)
        assertEquals("app_usage:reading articles", app?.stableKey)
        assertEquals("Zopy usually uses Chrome for reading articles", app?.fact)
        assertEquals(MemoryCategory.WORKFLOW, workflow?.category)
        assertEquals("workflow:app releases", workflow?.stableKey)
    }

    @Test fun learnsOnlyExplicitSuccessfulSolution() {
        val solution = AutomaticMemoryExtractor.extract(
            "Clearing the app cache worked for me for the login loop"
        )

        assertEquals(MemoryCategory.SOLUTION, solution?.category)
        assertEquals("solution:the login loop", solution?.stableKey)
        assertNull(AutomaticMemoryExtractor.extract("Maybe clearing cache could help"))
    }

    @Test fun newCategoriesStillRejectSensitiveInformation() {
        assertNull(AutomaticMemoryExtractor.extract("I usually use Notes for passwords"))
        assertNull(AutomaticMemoryExtractor.extract("Give me simple answers with my PIN 1234"))
        assertNull(AutomaticMemoryExtractor.extract("My bank token fix worked for me for login"))
    }
}
