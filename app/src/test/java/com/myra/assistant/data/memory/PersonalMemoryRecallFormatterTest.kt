package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalMemoryRecallFormatterTest {
    @Test
    fun recallsAgeAndLatestFriendNaturallyWithoutRoboticPrefix() {
        assertEquals(
            "Aysha tumhari best friend hai, aur tum 26 saal ke ho.",
            PersonalMemoryRecallFormatter.format(
                listOf("Zopy's best friend is Aysha", "Zopy is 26 years old")
            )
        )
    }

    @Test
    fun doesNotAddAnUnnecessaryFollowUpQuestion() {
        assertEquals(
            "Tum 26 saal ke ho.",
            PersonalMemoryRecallFormatter.format(listOf("Zopy is 26 years old"))
        )
    }
}
