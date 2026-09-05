package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalMemoryContextCorrectionTest {
    private val pendingFriend = PersonalMemoryExtractor.extract("Karima meri best friend hai")!!

    @Test
    fun resolvesShortCorrectionAgainstPendingFriendContext() {
        val corrected = PersonalMemoryContextCorrection.resolve("Nahi, Aisha", pendingFriend)

        assertEquals("person:best_friend", corrected?.stableKey)
        assertEquals("Zopy's best friend is Aisha", corrected?.fact)
    }

    @Test
    fun doesNotApplyFriendCorrectionToAnotherMemoryCategory() {
        val pendingAge = PersonalMemoryExtractor.extract("main 26 saal ka hoon")!!

        assertNull(PersonalMemoryContextCorrection.resolve("Nahi, Aisha", pendingAge))
    }
}
