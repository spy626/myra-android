package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalMemoryExtractorTest {
    @Test
    fun extractsEnglishAndHinglishAge() {
        val english = PersonalMemoryExtractor.extract("I am 26 years old")
        val hinglish = PersonalMemoryExtractor.extract("main 26 saal ka hoon")

        assertEquals("Zopy is 26 years old", english?.fact)
        assertEquals("identity:age", hinglish?.stableKey)
        assertEquals(MemorySensitivity.PERSONAL, english?.sensitivity)
    }

    @Test
    fun extractsRelationshipGoalProjectAndHabit() {
        assertEquals(
            "Zopy's best friend is Aisha",
            PersonalMemoryExtractor.extract("Aisha meri best friend hai")?.fact
        )
        assertEquals(
            MemoryCategory.GOAL,
            PersonalMemoryExtractor.extract("mera goal Android developer banna hai")?.category
        )
        assertEquals(
            MemoryCategory.PROJECT,
            PersonalMemoryExtractor.extract("I am building a LYRA Android app")?.category
        )
        val habit = PersonalMemoryExtractor.extract("main roz walk karta hoon")
        assertEquals(MemoryCategory.HABIT, habit?.category)
        assertTrue(habit?.stableKey?.startsWith("habit:") == true)
    }

    @Test
    fun handlesObservedVoiceTranscriptionMistakes() {
        val age = PersonalMemoryExtractor.extract("maim 26 sala ka hum.")
        assertEquals("Zopy is 26 years old", age?.fact)

        val friend = PersonalMemoryExtractor.extract("ayusa meri besta phrenda hai.")
        assertEquals(MemoryCategory.PERSON, friend?.category)
        assertEquals("person:best_friend", friend?.stableKey)
        assertEquals("Zopy's best friend is ayusa", friend?.fact)
        assertEquals(
            "Zopy is 26 years old",
            PersonalMemoryExtractor.extract("maim 26 sala ka hum?!")?.fact
        )
    }

    @Test
    fun rejectsNearMatchesOutsideClearPersonalStatements() {
        assertNull(PersonalMemoryExtractor.extract("mere dost ka time nahi aaya"))
        assertNull(PersonalMemoryExtractor.extract("best friend movie dikhao"))
        assertNull(PersonalMemoryExtractor.extract("26 hours me kitna time hota hai"))
    }

    @Test
    fun rejectsSecretsSensitiveAndAmbiguousStatements() {
        assertNull(PersonalMemoryExtractor.extract("my bank account number is 12345"))
        assertNull(PersonalMemoryExtractor.extract("my diagnosis is diabetes"))
        assertNull(PersonalMemoryExtractor.extract("my fear is heights"))
        assertNull(PersonalMemoryExtractor.extract("She meri best friend hai"))
        assertNull(PersonalMemoryExtractor.extract("meri dost ka message nahi aaya"))
    }

    @Test
    fun rejectsInvalidAgeAndOrdinaryConversation() {
        assertNull(PersonalMemoryExtractor.extract("I am 3 years old"))
        assertNull(PersonalMemoryExtractor.extract("24 hours me kitna time hota hai"))
        assertNull(PersonalMemoryExtractor.extract("mujhe horror movies pasand hain"))
    }

    @Test
    fun correctionUsesTheSameBestFriendSlotWithTheNewName() {
        val direct = PersonalMemoryExtractor.extract("Karima meri best friend hai")!!
        val corrected = PersonalMemoryExtractor.extract("Karima nahi Aysha meri best friend hai")!!

        assertEquals("person:best_friend", direct.stableKey)
        assertEquals(direct.stableKey, corrected.stableKey)
        assertEquals("Zopy's best friend is Aysha", corrected.fact)
    }

    @Test
    fun handlesObservedLiveCorrectionTranscriptionMistakes() {
        val observed = PersonalMemoryExtractor.extract(
            "Arima mane Ayasa mere besta phrenda hai"
        )
        val directCorrection = PersonalMemoryExtractor.extract(
            "Nahi meri best friend Aisha hai"
        )

        assertEquals("person:best_friend", observed?.stableKey)
        assertEquals("Zopy's best friend is Ayasa", observed?.fact)
        assertEquals("Zopy's best friend is Aisha", directCorrection?.fact)
    }

    @Test
    fun collapsesAdjacentNearDuplicateNamesFromLiveAsr() {
        val candidate = PersonalMemoryExtractor.extract("Karima Amira meri best friend hai")

        assertEquals("Zopy's best friend is Karima", candidate?.fact)
    }
}
