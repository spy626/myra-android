package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWebLinkIntentTest {
    @Test fun broadLinkKindsBecomeReadOnlyWebDiscovery() {
        val github = WorkspaceWebLinkIntent.decide("GitHub pe AIRI repo ka link do")
        assertEquals("github.com", github?.preferredHost)
        assertEquals("AIRI repo", github?.query)

        val docs = WorkspaceWebLinkIntent.decide("OpenAI API docs ka exact link bhejo")
        assertEquals("openai.com", docs?.preferredHost)
        assertTrue(docs?.query?.contains("API", ignoreCase = true) == true)

        val product = WorkspaceWebLinkIntent.decide("Samsung S25 product page ka link do")
        assertNull(product?.preferredHost)
        assertTrue(product?.query?.contains("Samsung", ignoreCase = true) == true)

        val video = WorkspaceWebLinkIntent.decide("YouTube pe CarryMinati latest video link do")
        assertEquals("youtube.com", video?.preferredHost)
        assertEquals("CarryMinati latest video", video?.query)
    }

    @Test fun platformPostpositionsDoNotSwallowActualProjectNames() {
        assertEquals("AIRI repo",
            WorkspaceWebLinkIntent.decide("GitHub par AIRI repo ka link do")?.query)
        assertEquals("AIRI repo",
            WorkspaceWebLinkIntent.decide("GitHub per AIRI repo ka link do")?.query)
        assertEquals("Pe repo",
            WorkspaceWebLinkIntent.decide("GitHub pe Pe repo ka link do")?.query)
        assertEquals("pe-audio repo",
            WorkspaceWebLinkIntent.decide("GitHub pe pe-audio repo ka link do")?.query)
        assertEquals("AIRI repo",
            WorkspaceWebLinkIntent.decide("AIRI repo GitHub pe link bhejo")?.query)
        assertEquals("API docs",
            WorkspaceWebLinkIntent.decide("OpenAI pe API docs ka link bhejo")?.query)
    }

    @Test fun writesSecretsBarePlatformsAndDirectUrlsNeverSearch() {
        assertNull(WorkspaceWebLinkIntent.decide("YouTube ka link bhejo"))
        assertNull(WorkspaceWebLinkIntent.decide("GitHub link feature add karo"))
        assertNull(WorkspaceWebLinkIntent.decide("https://example.com check karo"))
        assertNull(WorkspaceWebLinkIntent.decide("my API key ka docs link do"))
    }
}
