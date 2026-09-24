package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceZaiFreeTest {
    private fun body(request: Request) =
        JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())

    @Test fun exactCodingModelAllowlistAndDirectEndpoint() {
        assertEquals("glm-4.7-flash", WorkspaceZaiFree.textModel("glm-4.7-flash"))
        assertTrue(runCatching { WorkspaceZaiFree.textModel("glm-4.5-flash") }.isFailure)
        assertTrue(runCatching { WorkspaceZaiFree.textModel("glm-4.7-flashx") }.isFailure)
        val request = WorkspaceZaiFree.editRequest("zai-only-key", "replace selected file",
            WorkspaceZaiFree.DEFAULT_TEXT_MODEL, sourceApproved = true)
        assertEquals("api.z.ai", request.url.host)
        assertEquals("Bearer zai-only-key", request.header("Authorization"))
        assertFalse(request.url.toString().contains("zai-only-key"))
        val data = body(request)
        assertEquals("glm-4.7-flash", data.getString("model"))
        assertFalse(data.getBoolean("stream"))
        assertFalse(data.has("provider"))
        assertFalse(data.has("plugins"))
        assertFalse(data.toString().contains("zai-only-key"))
    }

    @Test fun oneFileCodingClientIsBoundedAndNeverRetries() {
        assertEquals(15_000, WorkspaceZaiFree.client.connectTimeoutMillis)
        assertEquals(20_000, WorkspaceZaiFree.client.writeTimeoutMillis)
        assertEquals(40_000, WorkspaceZaiFree.client.readTimeoutMillis)
        assertEquals(45_000, WorkspaceZaiFree.client.callTimeoutMillis)
        assertFalse(WorkspaceZaiFree.client.retryOnConnectionFailure)
        assertFalse(WorkspaceZaiFree.client.followRedirects)
        assertFalse(WorkspaceZaiFree.client.followSslRedirects)
    }

    @Test fun codingSourceNeedsSeparateConsent() {
        assertTrue(runCatching {
            WorkspaceZaiFree.editRequest("zai-only-key", "replace selected file",
                WorkspaceZaiFree.DEFAULT_TEXT_MODEL, sourceApproved = false)
        }.isFailure)
        val snapshot = WorkspaceWebsiteGeneration.Snapshot("site", "task", "spec",
            "Build a dark landing page", mapOf(
                "index.html" to null, "style.css" to null, "script.js" to null))
        assertTrue(runCatching {
            WorkspaceZaiFree.websiteRequest("zai-only-key", snapshot,
                WorkspaceZaiFree.DEFAULT_TEXT_MODEL, sourceApproved = false)
        }.isFailure)
        val website = WorkspaceZaiFree.websiteRequest("zai-only-key", snapshot,
            WorkspaceZaiFree.DEFAULT_TEXT_MODEL, sourceApproved = true)
        assertEquals(WorkspaceZaiFree.ENDPOINT, website.url.toString())
        val websiteBody = body(website)
        assertEquals(WorkspaceZaiFree.DEFAULT_TEXT_MODEL, websiteBody.getString("model"))
        assertFalse(websiteBody.has("provider"))
        assertFalse(websiteBody.has("plugins"))
        assertFalse(websiteBody.has("response_format"))
        assertEquals(2, websiteBody.getJSONArray("messages").length())
        val system = websiteBody.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("Return exactly THREE complete files as consecutive labelled code blocks"))
        assertFalse(system.contains("Return exactly ONE JSON object"))
        assertTrue(system.contains("No preface, extra block, duplicated file or trailing explanation."))
        assertTrue(websiteBody.toString().contains("Build a dark landing page"))
        assertTrue(runCatching {
            WorkspaceZaiFree.websiteRequest("zai-only-key", snapshot,
                "glm-4.5-flash", sourceApproved = true)
        }.isFailure)
    }

    @Test fun websiteClientHasResponsiveBoundedWindow() {
        assertEquals(15_000, WorkspaceZaiFree.websiteClient.connectTimeoutMillis)
        assertEquals(20_000, WorkspaceZaiFree.websiteClient.writeTimeoutMillis)
        assertEquals(60_000, WorkspaceZaiFree.websiteClient.readTimeoutMillis)
        assertEquals(75_000, WorkspaceZaiFree.websiteClient.callTimeoutMillis)
        assertFalse(WorkspaceZaiFree.websiteClient.retryOnConnectionFailure)
        assertFalse(WorkspaceZaiFree.websiteClient.followRedirects)
        assertFalse(WorkspaceZaiFree.websiteClient.followSslRedirects)
    }

    @Test fun businessCodeClassifies429WithoutLeakingProviderMessage() {
        val request = Request.Builder().url(WorkspaceZaiFree.ENDPOINT).build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(429).message("Too Many Requests")
            .body("{\"error\":{\"code\":\"1305\",\"message\":\"SECRET token=abc\"}}"
                .toResponseBody("application/json".toMediaType())).build()
        val failure = runCatching { WorkspaceZaiFree.read(response) }.exceptionOrNull()
            as WorkspaceZaiFree.RateLimitException
        assertEquals("1305", failure.businessCode)
        assertTrue(failure.message.orEmpty().contains("temporarily overloaded"))
        assertFalse(failure.message.orEmpty().contains("SECRET"))
        assertFalse(failure.message.orEmpty().contains("token=abc"))
        assertTrue(failure.message.orEmpty().contains("No automatic retry"))
    }

    @Test fun malformedBusinessCodeAndIncompleteReplyFailSafely() {
        val request = Request.Builder().url(WorkspaceZaiFree.ENDPOINT).build()
        val malformed = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(429).message("Too Many Requests")
            .body("{\"error\":{\"code\":\"abc\",\"message\":\"PRIVATE\"}}"
                .toResponseBody("application/json".toMediaType())).build()
        val ex = runCatching { WorkspaceZaiFree.read(malformed) }.exceptionOrNull()
            as WorkspaceZaiFree.RateLimitException
        assertNull(ex.businessCode)
        assertFalse(ex.message.orEmpty().contains("PRIVATE"))

        val partial = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("ok")
            .body("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}"
                .toResponseBody("application/json".toMediaType())).build()
        assertTrue(runCatching { WorkspaceZaiFree.read(partial) }.isFailure)
    }
}
