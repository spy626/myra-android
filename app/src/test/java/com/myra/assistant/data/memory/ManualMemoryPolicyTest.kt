package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualMemoryPolicyTest {
    @Test fun preservesExplicitFactWithoutInventingMeaning() {
        val candidate = ManualMemoryPolicy.candidate(
            "  Zopy visited Munnar last year  ",
            MemoryCategory.LIFE_EVENT,
            "manual:test"
        )!!
        assertEquals("Zopy visited Munnar last year", candidate.fact)
        assertEquals("manual:test", candidate.stableKey)
        assertEquals(ManualMemoryPolicy.SOURCE, candidate.source)
        assertTrue(candidate.explicitlyRequested)
    }

    @Test fun rejectsBlankOrOversizedDrafts() {
        assertNull(ManualMemoryPolicy.candidate(" ", MemoryCategory.PERSON))
        assertNull(ManualMemoryPolicy.candidate("x".repeat(201), MemoryCategory.PERSON))
    }
}
