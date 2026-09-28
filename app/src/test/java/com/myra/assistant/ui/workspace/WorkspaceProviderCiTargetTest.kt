package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceProviderCiTargetTest {
    private val mixed = listOf(" Kotlin ", "LYRA", "", " android ", "kotlin", "  ")

    @Test fun normalizeHonorsCrossFileContract() {
        assertEquals(
            listOf("android", "kotlin", "lyra"),
            WorkspaceProviderCiRules.normalize(mixed),
        )
        assertEquals(
            listOf("a", "b"),
            WorkspaceProviderCiRules.normalize(listOf(" B ", "a", "A", "b", " a ")),
        )
    }

    @Test fun previewUsesNormalizedValuesAndLimit() {
        assertEquals("android|kotlin", WorkspaceProviderCiTarget.preview(mixed, 2))
        assertEquals("android|kotlin|lyra", WorkspaceProviderCiTarget.preview(mixed, 99))
        assertEquals("", WorkspaceProviderCiTarget.preview(mixed, 0))
        assertEquals("", WorkspaceProviderCiTarget.preview(mixed, -4))
    }

    @Test fun countUsesNormalizedUniqueValues() {
        assertEquals(3, WorkspaceProviderCiTarget.count(mixed))
        assertEquals(2, WorkspaceProviderCiTarget.count(listOf(" A ", "a", " B ", "b")))
    }

    @Test fun hiddenBlankOnlyEdgeCaseIsConsistentAcrossFiles() {
        val blanks = listOf("", " ", "   ")
        assertEquals(emptyList<String>(), WorkspaceProviderCiRules.normalize(blanks))
        assertEquals("", WorkspaceProviderCiTarget.preview(blanks, 4))
        assertEquals(0, WorkspaceProviderCiTarget.count(blanks))
    }
}
