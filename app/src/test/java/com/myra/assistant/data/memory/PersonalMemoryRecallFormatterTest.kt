package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalMemoryRecallFormatterTest {
    @Test fun formatsLegacyModelBestFriendFactNaturally() {
        assertEquals(
            "Naufal tumhari best friend hai.",
            PersonalMemoryRecallFormatter.format(listOf("The user's best friend is Naufal"))
        )
    }

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

    @Test
    fun formatsValidPreferenceAndHidesLegacyMalformedPreference() {
        assertEquals(
            "Tumhe horror movies pasand hain.",
            PersonalMemoryRecallFormatter.format(
                listOf("Zopy likes na ghumana", "Zopy likes horror movies")
            )
        )
    }

    @Test
    fun recallsTwoBestFriendsWhenUserExplicitlyKeptBoth() {
        assertEquals(
            "Karima aur Kareem tumhari best friends hain.",
            PersonalMemoryRecallFormatter.format(
                listOf("Zopy's best friend is Karima", "Kareem is Zopy's male best friend")
            )
        )
    }

    @Test fun recallsThreeBestFriendsAsOneNaturalSentence() {
        assertEquals(
            "Kareem, Ayesha aur Naufal tumhari best friends hain.",
            PersonalMemoryRecallFormatter.format(
                listOf(
                    "Zopy's best friend is Kareem",
                    "Zopy's best friend is Ayesha",
                    "The user's best friend is Naufal"
                )
            )
        )
    }
}
