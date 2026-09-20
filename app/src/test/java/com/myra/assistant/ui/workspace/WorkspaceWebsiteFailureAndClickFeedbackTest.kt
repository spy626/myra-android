package com.myra.assistant.ui.workspace

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteFailureAndClickFeedbackTest {
    private val goal = "Minicoy website: Welcome to Minicoy heading, Explore Minicoy button; " +
        "Things to Explore section. Button tap karne par Things to Explore tak scroll ho aur " +
        "Exploring Minicoy! text clearly dikhe."
    private val snapshot = WorkspaceWebsiteGeneration.Snapshot(
        "site", "task", "approved", goal,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))

    private fun body(request: okhttp3.Request): JSONObject {
        val out = Buffer()
        requireNotNull(request.body).writeTo(out)
        return JSONObject(out.readUtf8())
    }

    @Test fun freshSiteTextFirstAndCompatibleJsonModeKeepSameFreeModelAndApprovedSource() {
        val first = body(WorkspaceWebsiteGroqFallback.request("gsk_test", snapshot))
        val compatible = body(WorkspaceWebsiteGroqFallback.compatibilityRequest("gsk_test", snapshot))
        assertTrue(WorkspaceWebsiteGroqFallback.usesFreshTextMode(snapshot))
        assertEquals(first.getString("model"), compatible.getString("model"))
        assertEquals(first.getJSONArray("messages").toString(), compatible.getJSONArray("messages").toString())
        assertFalse(first.has("response_format"))
        assertEquals("json_object", compatible.getJSONObject("response_format").getString("type"))
        assertFalse(compatible.has("provider"))
        assertFalse(compatible.has("plugins"))
        assertTrue(runCatching { WorkspaceWebsiteGeneration.parse("not json") }.isFailure)
        assertTrue(runCatching { WorkspaceWebsiteGeneration.parse("{\"files\":{}}") }.isFailure)
    }

    @Test fun providerErrorCategoryNeverEchoesSecretsOrSource() {
        val source = "gsk_private_key_DoNotShow My sensitive website code"
        val body = JSONObject().put("error", JSONObject().put("code", "json_validate_failed")
            .put("message", "Generated JSON does not match schema; $source")).toString()
        assertEquals("generated JSON rejected by provider", WorkspaceWebsiteProviderError.category(body))
        assertEquals("response format rejected", WorkspaceWebsiteProviderError.category(
            JSONObject().put("error", JSONObject().put("message", "response_format json_schema invalid $source")).toString()))
        assertEquals("reason unavailable", WorkspaceWebsiteProviderError.category("not-json $source"))
        assertFalse(WorkspaceWebsiteProviderError.category(body).contains(source))
        assertEquals("reason unavailable", WorkspaceWebsiteProviderError.category("a".repeat(8_193)))
    }

    @Test fun requestedTapFeedbackIsVisibleViaNativeTargetAndIdempotent() {
        val input = mapOf("index.html" to "<html><head></head><body>" +
            "<h1>Welcome to Minicoy</h1><button>Explore Minicoy</button>" +
            "<h2>Things to Explore</h2></body></html>",
            "style.css" to "body { margin: 0; }", "script.js" to "")
        val first = WorkspaceWebsiteVisualQuality.review(snapshot, input)
        val html = first.files.getValue("index.html")
        val css = first.files.getValue("style.css")
        assertTrue(html.contains("href=\"#lyra-explore-section\""))
        assertTrue(html.contains("class=\"lyra-explore-feedback\""))
        assertTrue(html.contains("Exploring Minicoy!"))
        assertTrue(css.contains(".lyra-explore-target:target + .lyra-explore-feedback { display: block;"))
        assertEquals(1, Regex("Exploring Minicoy!").findAll(html).count())
        assertEquals(first.files, WorkspaceWebsiteVisualQuality.review(snapshot, first.files).files)
    }

    @Test fun unrelatedSiteDoesNotAcquireMinicoySpecificFeedback() {
        val unrelated = snapshot.copy(goal = "Build a booking site")
        val result = WorkspaceWebsiteActionQuality.review(unrelated, mapOf(
            "index.html" to "<html><body><button>Explore Minicoy</button>" +
                "<h2>Things to Explore</h2></body></html>",
            "style.css" to "", "script.js" to ""))
        assertFalse(result.repaired)
        assertFalse(result.files.getValue("index.html").contains("lyra-explore-feedback"))
    }
}
