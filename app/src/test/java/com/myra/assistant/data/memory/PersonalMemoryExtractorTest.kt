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
}
