package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSourcePresentationTest {
    private fun source(
        title: String = "A very long response headline — GitHub — AI companion on GitHub",
        url: String = "https://github.com/moeru-ai/airi",
        snippet: String = "AIRI source code and documentation",
        label: String? = "Live web result · reachable",
    ) = WorkspaceVerifiedSourceStore.Source(
        title = title, url = url, snippet = snippet, observedAtMs = 1000L,
        verifiedLabel = label,
    )

    @Test fun githubRepositoryTitleComesFromDestinationNotLongHtmlTitle() {
        val result = WorkspaceSourcePresentation.display(source())
        assertEquals("moeru-ai/airi — GitHub repository", result.title)
        assertEquals("https://github.com/moeru-ai/airi", result.url)
        assertEquals("github.com  ·  Live web result · reachable", result.domainAndStatus)
        assertEquals("AIRI source code and documentation", result.description)
        assertFalse(result.title.contains("A very long"))
    }

    @Test fun descriptionsEndAtWordBoundaryAndDoNotClaimOfficialStatus() {
        val text = "A public GitHub repository description includes a thoughtful explanation of the project and its development history and various other features."
        val result = WorkspaceSourcePresentation.display(
            source(title = "A generic page", url = "https://example.org/page",
                snippet = text.repeat(3), label = null)
        )
        assertTrue(result.description.endsWith("…"))
        assertTrue(result.description.length <= 205)
        assertTrue(text.repeat(3).contains(result.description.dropLast(1) + " "))
        assertEquals("example.org", result.domainAndStatus)
        assertEquals("A generic page", result.title)
        assertFalse(result.domainAndStatus.contains("Official"))
    }

    @Test fun compactHeightCapAndLongSourceCollectionAreBounded() {
        assertEquals(620, WorkspaceSourcePresentation.maxScrollHeightPx(1000))
        assertTrue(WorkspaceSourcePresentation.maxScrollHeightPx(2000) < 2000)
        assertEquals(1, WorkspaceSourcePresentation.maxScrollHeightPx(0))
    }

    @Test fun veryLongGenericPageTitlesAreReadable() {
        val input = "An interesting and extremely detailed article about web development and applications and interactions for mobile clients"
        val result = WorkspaceSourcePresentation.display(
            source(title = input, url = "https://example.org/article")
        )
        assertTrue(result.title.length <= 80)
        assertTrue(result.title.endsWith("…"))
        assertFalse(result.title.dropLast(1).endsWith(" "))
    }
}
