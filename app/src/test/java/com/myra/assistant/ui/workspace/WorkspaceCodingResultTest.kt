package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCodingResultTest {
    @Test fun summaryListsOnlyActualChangedPathsAndHtmlHeadings() {
        val before = mapOf("index.html" to "<html>old</html>", "style.css" to "body{}", "script.js" to "ok()")
        val generated = mapOf("index.html" to "<html><body><h1>Welcome to Minicoy</h1>" +
            "<h2>Things to Explore</h2></body></html>", "style.css" to "body{}", "script.js" to "ok()")
        val reply = WorkspaceCodingResult.websiteSuccess(before, generated)
        assertTrue(reply.contains("index.html"))
        assertFalse(reply.contains("style.css"))
        assertFalse(reply.contains("script.js"))
        assertTrue(reply.contains("Things to Explore"))
        assertTrue(reply.contains("Preview"))
        assertFalse(reply.contains("tested successfully"))
    }
    @Test fun unchangedWebsiteDoesNotClaimNewEdit() {
        val files = WorkspaceWebsiteGeneration.PATHS.associateWith { "same" }
        assertTrue(WorkspaceCodingResult.websiteSuccess(files, files).contains("no content changes"))
    }
    @Test fun timeoutIsFailureNotSuccess() {
        val reply = WorkspaceCodingResult.failure("Website provider timed out")
        assertTrue(reply.contains("timed out"))
        assertTrue(reply.contains("dobara"))
        assertFalse(reply.contains("Website files saved"))
    }
}
