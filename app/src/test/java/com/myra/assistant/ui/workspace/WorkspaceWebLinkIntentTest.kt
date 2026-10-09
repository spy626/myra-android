package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWebLinkIntentTest {
    @Test fun broadLinkKindsBecomeReadOnlyWebDiscovery() {
        val github = WorkspaceWebLinkIntent.decide("GitHub pe AIRI repo ka link do")
        assertEquals("github.com", github?.preferredHost)
        assertTrue(github?.query?.contains("AIRI", ignoreCase = true) == true)

        val docs = WorkspaceWebLinkIntent.decide("OpenAI API docs ka exact link bhejo")
        assertEquals("openai.com", docs?.preferredHost)
        assertTrue(docs?.query?.contains("API", ignoreCase = true) == true)

        val product = WorkspaceWebLinkIntent.decide("Samsung S25 product page ka link do")
        assertNull(product?.preferredHost)
        assertTrue(product?.query?.contains("Samsung", ignoreCase = true) == true)

        val video = WorkspaceWebLinkIntent.decide("YouTube pe CarryMinati latest video link do")
        assertEquals("youtube.com", video?.preferredHost)
        assertTrue(video?.query?.contains("CarryMinati", ignoreCase = true) == true)
    }

    @Test fun writesSecretsBarePlatformsAndDirectUrlsNeverSearch() {
        assertNull(WorkspaceWebLinkIntent.decide("YouTube ka link bhejo"))
        assertNull(WorkspaceWebLinkIntent.decide("GitHub link feature add karo"))
        assertNull(WorkspaceWebLinkIntent.decide("https://example.com check karo"))
        assertNull(WorkspaceWebLinkIntent.decide("my API key ka docs link do"))
    }
}
