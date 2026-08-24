package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalMemoryPermissionPromptTest {
    @Test
    fun repeatsInterpretedAgeForVerification() {
        val candidate = PersonalMemoryExtractor.extract("maim 26 sala ka hum")!!
        assertEquals(
            "Maine samjha tum 26 saal ke ho. Kya main ise yaad rakhun?",
            PersonalMemoryPermissionPrompt.format(candidate)
        )
    }

    @Test
    fun repeatsInterpretedFriendNameForVerification() {
        val candidate = PersonalMemoryExtractor.extract("ayusa meri besta phrenda hai")!!
        assertEquals(
            "Maine samjha ayusa tumhari best friend hai. Kya main ise yaad rakhun?",
            PersonalMemoryPermissionPrompt.format(candidate)
        )
    }
}
