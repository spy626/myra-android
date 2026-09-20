package com.myra.assistant.ui.workspace

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteGroqFallbackTest {
    private fun sample(html: String? = null) = WorkspaceWebsiteGeneration.Snapshot(
        "site", "task", "approved", "Update the Minicoy website", mapOf(
            "index.html" to html, "style.css" to "body { margin: 0; }",
            "script.js" to "console.log('Minicoy')"))

    @Test fun final429OnlyWithSeparateWebsiteAndGroqFreeConsentAndKey() {
        val allowed = WorkspaceWebsiteGroqFallback::eligible
        assertTrue(allowed(429, true, true, "gsk_test_key"))
        for (code in listOf(400, 401, 402, 403, 408, 500, 502, 503, 504)) {
            assertFalse(allowed(code, true, true, "gsk_test_key"))
        }
        assertFalse(allowed(429, false, true, "gsk_test_key"))
        assertFalse(allowed(429, true, false, "gsk_test_key"))
        assertFalse(allowed(429, true, true, ""))
        assertFalse(allowed(429, true, true, "bad key"))
    }

    @Test fun resendsOnlyApprovedWebsiteSnapshotWithGroqJsonModeAndNoPaidRouting() {
        val request = WorkspaceWebsiteGroqFallback.request("gsk_test_key", sample("<html>Old page</html>"))
        assertEquals(WorkspaceGroqFree.ENDPOINT, request.url.toString())
        assertEquals("Bearer gsk_test_key", request.header("Authorization"))
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        assertEquals(WorkspaceGroqFree.MODEL, body.getString("model"))
        assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
        val envelope = body.getJSONObject("response_format").getJSONObject("json_schema")
        assertTrue(envelope.getBoolean("strict"))
        val schema = envelope.getJSONObject("schema")
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(listOf("files"), (0 until schema.getJSONArray("required").length()).map {
            schema.getJSONArray("required").getString(it)
        })
        val files = schema.getJSONObject("properties").getJSONObject("files")
        assertFalse(files.getBoolean("additionalProperties"))
        assertEquals(WorkspaceWebsiteGeneration.PATHS.toSet(),
            (0 until files.getJSONArray("required").length()).map {
                files.getJSONArray("required").getString(it)
            }.toSet())
        assertFalse(body.has("reasoning_format")) // Unsupported by Groq GPT-OSS; caused HTTP 400.
        assertFalse(body.getBoolean("include_reasoning"))
        assertEquals(4500, body.getInt("max_completion_tokens"))
        assertFalse(body.has("max_tokens"))
        assertFalse(body.has("provider"))
        assertFalse(body.has("plugins"))
        assertEquals(2, body.getJSONArray("messages").length())
        val context = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("Update the Minicoy website", context.getString("goal"))
        assertEquals("<html>Old page</html>", context.getJSONObject("existingFiles").getString("index.html"))
        assertEquals(WorkspaceWebsiteGeneration.PATHS.toSet(),
            context.getJSONObject("existingFiles").keys().asSequence().toSet())
        assertFalse(buffer.toString().contains("gsk_test_key"))
    }

    @Test fun oversizedWebsiteContextRefusesFallbackBeforeAnyNetworkCall() {
        val huge = sample("a".repeat(12_000))
        assertTrue(runCatching { WorkspaceWebsiteGroqFallback.request("gsk_test_key", huge) }.isFailure)
    }

    @Test fun GroqFallbackClientCannotRetryOrResendAfter429() {
        assertTrue(WorkspaceWebsiteGroqFallback.client.interceptors.none { it is WorkspaceFreeRouteRetry })
        assertFalse(WorkspaceWebsiteGroqFallback.client.retryOnConnectionFailure)
    }

    @Test fun invalidKeyNeverBuildsGroqRequest() {
        assertTrue(runCatching { WorkspaceWebsiteGroqFallback.request("bad key", sample()) }.isFailure)
    }
}
