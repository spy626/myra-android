package com.myra.assistant.ui.workspace

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteRecoveryTest {
    private val snapshot = WorkspaceWebsiteGeneration.Snapshot(
        "site", "task", "approved", "Build a Minicoy website",
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))

    private fun payload(request: okhttp3.Request): JSONObject {
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    @Test fun onlyDefinitivelyRejectedGroqPrimary400GetsCompatibilityAttempt() {
        val eligible = WorkspaceWebsiteGroqFallback::compatibilityEligible
        assertTrue(eligible(WorkspaceWebsiteRoute.Provider.GROQ, 400))
        for (code in listOf(401, 403, 404, 408, 429, 500, 502, 503, 504)) {
            assertFalse(eligible(WorkspaceWebsiteRoute.Provider.GROQ, code))
        }
        assertFalse(eligible(WorkspaceWebsiteRoute.Provider.OPENROUTER, 400))
    }

    @Test fun compatibleRequestUsesSameApprovedContextFreeModelAndStrictLocalParsing() {
        val original = WorkspaceWebsiteGroqFallback.request("gsk_test_key", snapshot)
        val recovery = WorkspaceWebsiteGroqFallback.compatibilityRequest("gsk_test_key", snapshot)
        val first = payload(original)
        val second = payload(recovery)
        assertEquals(original.url, recovery.url)
        assertEquals(original.header("Authorization"), recovery.header("Authorization"))
        assertEquals(first.getJSONArray("messages").toString(), second.getJSONArray("messages").toString())
        assertEquals(first.getString("model"), second.getString("model"))
        assertEquals("json_schema", first.getJSONObject("response_format").getString("type"))
        assertFalse(second.has("response_format")) // last attempt is plain text, locally parsed
        assertFalse(second.has("provider"))
        assertFalse(second.has("plugins"))
        assertTrue(runCatching { WorkspaceWebsiteGeneration.parse("{\"files\":{}}") }.isFailure)
    }

    @Test fun newlyInventedImagesAreOmittedButExistingMarkupIsPreserved() {
        val html = "<html><body><h1>Things to Explore</h1>" +
            "<img src=\"https://example.com/not-a-real-beach.jpg\" alt=\"Beach\">" +
            "<img src=\"missing-local-photo.jpg\" alt=\"Lighthouse\">" +
            "<h2>Beaches</h2><h2>Lighthouse</h2></body></html>"
        val cleaned = WorkspaceWebsiteGeneration.omitUnverifiedImages(snapshot,
            mapOf("index.html" to html, "style.css" to "", "script.js" to ""))
        assertFalse(cleaned.getValue("index.html").contains("<img"))
        assertTrue(cleaned.getValue("index.html").contains("Things to Explore"))
        assertTrue(cleaned.getValue("index.html").contains("Lighthouse"))
        val existing = snapshot.copy(original = snapshot.original +
            ("index.html" to "<img src=\"existing.jpg\">"))
        val preserved = WorkspaceWebsiteGeneration.omitUnverifiedImages(existing,
            mapOf("index.html" to "<html><body><img src=\"existing.jpg\"></body></html>"))
        assertTrue(preserved.getValue("index.html").contains("existing.jpg"))
    }
}
