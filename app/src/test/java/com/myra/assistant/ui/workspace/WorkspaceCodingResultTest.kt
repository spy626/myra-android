package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCodingResultTest {
    @Test fun summaryIsConciseAndAvoidsDebugReceiptCopy() {
        val before = mapOf("index.html" to "<html>old</html>", "style.css" to "body{}", "script.js" to "ok()")
        val generated = mapOf("index.html" to "<html><body><h1>Welcome to Minicoy</h1>" +
            "<h2>Things to Explore</h2></body></html>", "style.css" to "body{}", "script.js" to "ok()")
        val reply = WorkspaceCodingResult.websiteSuccess(before, generated)
        assertTrue(reply.startsWith("Website is ready."))
        assertTrue(reply.contains("Preview"))
        assertFalse(reply.contains("index.html"))
        assertFalse(reply.contains("style.css"))
        assertFalse(reply.contains("script.js"))
        assertFalse(reply.contains("saved-file", ignoreCase = true))
        assertFalse(reply.contains("phone pass", ignoreCase = true))
        assertFalse(reply.contains("Things to Explore"))
    }

    @Test fun unchangedWebsiteDoesNotClaimNewEdit() {
        val files = WorkspaceWebsiteGeneration.PATHS.associateWith { "same" }
        val reply = WorkspaceCodingResult.websiteSuccess(files, files)
        assertTrue(reply.contains("already up to date"))
        assertFalse(reply.contains("Saved index.html"))
    }

    @Test fun timeoutIsFailureNotSuccess() {
        val reply = WorkspaceCodingResult.failure("Website provider timed out")
        assertTrue(reply.contains("timed out"))
        assertTrue(reply.contains("dobara"))
        assertFalse(reply.contains("Done"))
    }
}
