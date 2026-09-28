package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceProviderCiTargetTest {
    @Test fun emptyInputIsEmpty() {
        assertEquals("", WorkspaceProviderCiTarget.canonicalTags(emptyList()))
    }

    @Test fun normalizesDeduplicatesSortsAndJoins() {
        assertEquals(
            "android|kotlin|lyra",
            WorkspaceProviderCiTarget.canonicalTags(
                listOf(" Kotlin ", "LYRA", "", " android ", "kotlin", "  "),
            ),
        )
    }

    @Test fun duplicateIdentityIsAfterTrimAndLowercase() {
        assertEquals(
            "a|b",
            WorkspaceProviderCiTarget.canonicalTags(listOf(" B ", "a", "A", "b", " a ")),
        )
    }
}
