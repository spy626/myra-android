package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceMemoryIdentityTest {
    @Test fun semanticPreferenceAndCommunicationStyleShareVerbosityIdentity() {
        val semantic = MemoryCandidate(
            MemoryCategory.PREFERENCE,
            "Zopy prefers detailed explanations",
            "semantic:preference:answer_length",
            MemorySensitivity.LOW,
            .92
        )
        val communication = MemoryCandidate(
            MemoryCategory.COMMUNICATION_STYLE,
            "Zopy prefers short answers",
            "communication:response_style",
            MemorySensitivity.LOW,
            .95
        )

        val first = PreferenceMemoryIdentity.canonicalize(semantic)
        val second = PreferenceMemoryIdentity.canonicalize(communication)

        assertEquals(PreferenceMemoryIdentity.RESPONSE_VERBOSITY_KEY, first.stableKey)
        assertEquals(first.stableKey, second.stableKey)
        assertEquals(MemoryCategory.PREFERENCE, first.category)
        assertEquals(MemoryCategory.PREFERENCE, second.category)
    }
}
