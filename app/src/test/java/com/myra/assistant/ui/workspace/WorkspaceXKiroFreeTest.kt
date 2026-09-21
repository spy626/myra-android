package com.myra.assistant.ui.workspace

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Offline tests; never assert a live account or phone result. */
class WorkspaceXKiroFreeTest {
    private val free = """{"data":[{"id":"qwen/qwen3-coder-plus:free","access_tier":"free","max_output_tokens":8192,"pricing":{"input":0,"output":0}},{"id":"openai/gpt-5.6-sol","access_tier":"paid","pricing":{"input":1,"output":2}}]}"""
    private val snapshot = WorkspaceWebsiteGeneration.Snapshot("site", "task", "approval",
        "Create a responsive recipe website", mapOf("index.html" to null,
            "style.css" to "body{color:white}", "script.js" to null))

    @Test fun exactFreeRouteMustPassCatalogPriceAndQuota() {
        assertTrue(WorkspaceXKiroFree.catalogIsFree(free))
        assertFalse(WorkspaceXKiroFree.catalogIsFree(free, "openai/gpt-5.6-sol"))
        assertFalse(WorkspaceXKiroFree.catalogIsFree(free.replace("\"input\":0", "\"input\":0.01")))
        assertFalse(WorkspaceXKiroFree.catalogIsFree(free.replace("\"access_tier\":\"free\"", "\"access_tier\":\"paid\"")))
        assertFalse(WorkspaceXKiroFree.catalogIsFree("{\"data\":[]}"))
        assertFalse(WorkspaceXKiroFree.catalogIsFree("garbled"))
        assertTrue(WorkspaceXKiroFree.quotaIsFree("{\"free_tokens\":{\"remaining\":8000}}"))
        assertFalse(WorkspaceXKiroFree.quotaIsFree("{\"free_tokens\":{\"remaining\":7999}}"))
        assertFalse(WorkspaceXKiroFree.quotaIsFree("{\"free_tokens\":{\"remaining\":null}}"))
    }

    @Test fun websiteUsesExactFreeModelAndCanonicalApprovedSourceOnly() {
        val request = WorkspaceXKiroFree.websiteRequest("sk-xt-test", snapshot)
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        assertEquals(WorkspaceXKiroFree.ENDPOINT, request.url.toString())
        assertEquals(WorkspaceXKiroFree.MODEL, body.getString("model"))
        assertFalse(body.has("provider")); assertFalse(body.has("plugins"))
        assertFalse(body.has("response_format"))
        assertEquals(2, body.getJSONArray("messages").length())
        val context = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(snapshot.goal, context.getString("goal"))
        assertEquals("body{color:white}", context.getJSONObject("existingFiles").getString("style.css"))
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content")
            .contains("Return exactly THREE complete files"))
    }

    @Test fun badKeysAreRefusedWithoutSending() {
        for (key in listOf("", "sk-xt-a,b", "sk-xt- a", " ", "x".repeat(257))) {
            assertFalse(WorkspaceXKiroFree.validKey(key))
            assertTrue(runCatching { WorkspaceXKiroFree.websiteRequest(key, snapshot) }.isFailure)
        }
    }

    @Test fun existingProviderSelectionRemainsUnchangedUntilExplicitOptIn() {
        assertEquals(WorkspaceWebsiteRoute.Provider.OPENROUTER,
            WorkspaceWebsiteRoute.choose("or-key", "", false, false))
        assertEquals(WorkspaceWebsiteRoute.Provider.GROQ,
            WorkspaceWebsiteRoute.choose("", "gsk-test", true, false))
        assertNull(WorkspaceWebsiteRoute.choose("", "", false, false))
    }
}
