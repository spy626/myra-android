package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceProviderCiTargetTest {
    private val mixed = listOf(" Kotlin ", "LYRA", "", " android ", "kotlin", "  ")

    @Test fun emptyInputNormalizesToEmptyList() {
        assertEquals(emptyList<String>(), WorkspaceProviderCiTarget.normalizedTags(emptyList()))
    }

    @Test fun normalizesDeduplicatesAfterNormalizationAndSorts() {
        assertEquals(
            listOf("android", "kotlin", "lyra"),
            WorkspaceProviderCiTarget.normalizedTags(mixed),
        )
        assertEquals(
            listOf("a", "b"),
            WorkspaceProviderCiTarget.normalizedTags(listOf(" B ", "a", "A", "b", " a ")),
        )
    }

    @Test fun previewUsesNormalizedOrderAndLimit() {
        assertEquals("android|kotlin", WorkspaceProviderCiTarget.previewTags(mixed, 2))
        assertEquals("android|kotlin|lyra", WorkspaceProviderCiTarget.previewTags(mixed, 99))
        assertEquals("", WorkspaceProviderCiTarget.previewTags(mixed, 0))
    }

    @Test fun negativePreviewLimitIsSafelyEmpty() {
        assertEquals("", WorkspaceProviderCiTarget.previewTags(mixed, -3))
    }
}
