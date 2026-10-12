package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWebResearchIntentTest {
    @Test fun routesExplicitResearchQuestionWithoutRewritingItsFullText() {
        val input = "Android mein WorkManager aur Foreground Service mein kya difference hai? Reliable sources ke saath explain karo."
        val result = requireNotNull(WorkspaceWebResearchIntent.decide(input))
        assertEquals(input, result.question)
        assertEquals(3, result.search.maxResults)
        assertTrue(result.search.query.contains("WorkManager"))
        assertTrue(result.search.query.contains("Foreground"))
        assertTrue(result.search.query.contains("Service"))
    }
    @Test fun normalChatDownloadsLinksAndSensitiveOrWriteTasksAreNotHijacked() {
        assertNull(WorkspaceWebResearchIntent.decide("How is WorkManager different from Foreground Service?"))
        assertNull(WorkspaceWebResearchIntent.decide("Telegram app ka download links do"))
        assertNull(WorkspaceWebResearchIntent.decide("Kotlin coroutines documentation ke 3 links do"))
        assertNull(WorkspaceWebResearchIntent.decide("Please fix app search and explain sources"))
        assertNull(WorkspaceWebResearchIntent.decide("Explain https://example.com with sources"))
        assertNull(WorkspaceWebResearchIntent.decide("Explain my API key with sources"))
    }
}
