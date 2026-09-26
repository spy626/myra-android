package com.myra.assistant.ui.workspace

import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteTransportRecoveryTest {
    private val html = "<!doctype html><html><head><link rel=\"stylesheet\" href=\"style.css\"></head><body><h1>Welcome to Minicoy</h1><script src=\"script.js\"></script></body></html>"
    private val snapshot = WorkspaceWebsiteGeneration.Snapshot(
        "site", "task", "approved", "Build Minicoy: Explore Minicoy should scroll to Things to Explore and show Exploring Minicoy!",
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))

    private fun fenced(script: String = "", extra: String = "") =
        "index.html\n```html\n$html\n```\nstyle.css\n```css\n" +
            "body { color: #123; }\n```\nscript.js\n```javascript\n$script\n```$extra"

    @Test fun completeExplicitlyLabelledThreeFileResponseCanBeValidatedWithoutNetworkReplay() {
        val parsed = WorkspaceWebsiteGeneration.parse(fenced())
        assertEquals(html, parsed.getValue("index.html"))
        assertEquals("body { color: #123; }", parsed.getValue("style.css"))
        assertEquals("", parsed.getValue("script.js"))
    }

    @Test fun shortLeadingIntroCanRecoverButIncompleteAmbiguousOrExtraFilesFailClosed() {
        assertEquals(html, WorkspaceWebsiteGeneration.parse(
            "Here is your site:\n" + fenced()
        ).getValue("index.html"))
        listOf(
            fenced().substringBefore("script.js\n```javascript"),
            fenced(extra = "\nnotes.txt\n```text\nsecret\n```"),
            fenced() + "\nMore output",
            fenced() + "\n" + fenced(),
            fenced(script = "```\nother.txt\n```text\nnot part of this file")
        ).forEach { output ->
            assertTrue("Unsupported output must not be accepted", runCatching {
                WorkspaceWebsiteGeneration.parse(output)
            }.isFailure)
        }
    }

    @Test fun shortPrefaceBeforeSingleCompleteJsonFenceIsRecoverableButTrailingTextIsNot() {
        val payload = JSONObject().put("files", JSONObject()
            .put("index.html", html).put("style.css", "body {}")
            .put("script.js", "")).toString()
        assertEquals(html, WorkspaceWebsiteGeneration.parse(
            "Here are the three requested files:\n```json\n$payload\n```"
        ).getValue("index.html"))
        assertTrue(runCatching {
            WorkspaceWebsiteGeneration.parse("```json\n$payload\n```\nSecond output follows")
        }.isFailure)
    }

    @Test fun invalidTransportReportsOnlyBoundedCategoriesNeverProjectContents() {
        val privateValue = "SENSITIVE_SOURCE_SHOULD_NEVER_APPEAR"
        val inputs = mapOf(
            "{\"files\":{\"index.html\":\"$privateValue\"" to "incomplete_json",
            "```html\n$privateValue\n```" to "unrecognized_fence",
            "This is not JSON: $privateValue" to "non_json_output")
        inputs.forEach { (raw, category) ->
            val error = runCatching { WorkspaceWebsiteGeneration.parse(raw) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty().contains(category))
            assertFalse(error.message.orEmpty().contains(privateValue))
        }
    }

    private fun payload(request: Request): JSONObject {
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    @Test fun freshSiteRequestAssignsNavigationToNativeAnchorNotCompetingJavascript() {
        val request = payload(WorkspaceWebsiteGroqFallback.request("gsk_test_key", snapshot))
        val system = request.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("script.js must be an empty string"))
        assertTrue(system.contains("Do not attach Explore click listeners"))
        assertFalse(request.has("response_format"))
        val existing = snapshot.copy(original = mapOf("index.html" to html,
            "style.css" to "body{}", "script.js" to "console.log('existing');"))
        val oldSystem = payload(WorkspaceWebsiteGroqFallback.request("gsk_test_key", existing))
            .getJSONArray("messages").getJSONObject(0).getString("content")
        assertFalse(oldSystem.contains("script.js must be an empty string"))
    }
}
