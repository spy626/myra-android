package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticMemoryChangeParserTest {
    @Test fun ordinaryPreferenceProducesSave() {
        val change = AutomaticMemoryChangeParser.parse("I love horror movies")
        assertTrue(change is AutomaticMemoryChange.Save)
        assertEquals(
            "preference:likes:horror movies",
            (change as AutomaticMemoryChange.Save).candidate.stableKey
        )
    }

    @Test fun explicitNegativePreferenceRemovesMatchingMemory() {
        listOf(
            "I don't like horror movies anymore",
            "I no longer like horror movies",
            "mujhe horror movies ab pasand nahi hai"
        ).forEach { phrase ->
            assertEquals(
                phrase,
                "preference:likes:horror movies",
                (AutomaticMemoryChangeParser.parse(phrase) as AutomaticMemoryChange.Forget).stableKey
            )
        }
    }

    @Test fun correctionUpdatesReplaceableFavoriteKey() {
        val change = AutomaticMemoryChangeParser.parse(
            "Actually, my favorite game is Minecraft"
        ) as AutomaticMemoryChange.Save
        assertEquals("preference:favorite:game", change.candidate.stableKey)
        assertEquals("Zopy's favorite game is Minecraft", change.candidate.fact)
    }

    @Test fun correctedResponseStyleUsesSameStableKeyAsInitialPreference() {
        val initial = AutomaticMemoryChangeParser.parse(
            "Give me short answers"
        ) as AutomaticMemoryChange.Save
        val corrected = AutomaticMemoryChangeParser.parse(
            "Actually, give me detailed answers"
        ) as AutomaticMemoryChange.Save

        assertEquals("preference:response_style", initial.candidate.stableKey)
        assertEquals(initial.candidate.stableKey, corrected.candidate.stableKey)
        assertEquals("Zopy prefers detailed answers", corrected.candidate.fact)
    }

    @Test fun vagueAndPersonalNegativesDoNotChangeMemory() {
        assertNull(AutomaticMemoryChangeParser.parse("I don't like that anymore"))
        assertNull(AutomaticMemoryChangeParser.parse("mujhe meri dost ab pasand nahi hai"))
        assertNull(AutomaticMemoryChangeParser.parse("maybe I do not like horror movies"))
    }
}
