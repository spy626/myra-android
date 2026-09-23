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
    private val message = WorkspaceConversationStore.Message("one", "user", "Hindi mein jawab do", 1L)
    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())

    @Test fun exactAllowlistAndSeparateVoiceKey() {
        assertEquals("glm-4.7-flash", WorkspaceZaiFree.textModel(null))
        assertEquals("glm-4.5-flash", WorkspaceZaiFree.textModel("glm-4.5-flash"))
        assertTrue(runCatching { WorkspaceZaiFree.textModel("glm-4.7-flashx") }.isFailure)
        val request = WorkspaceZaiFree.request("zai-only-key", listOf(message), null,
            WorkspaceZaiFree.DEFAULT_TEXT_MODEL, false)
        val data = body(request)
        assertEquals("api.z.ai", request.url.host)
        assertEquals("glm-4.7-flash", data.getString("model"))
        assertTrue(data.getBoolean("stream"))
        assertEquals("disabled", data.getJSONObject("thinking").getString("type"))
        assertEquals("text/event-stream", request.header("Accept"))
        assertFalse(data.has("provider"))
        assertFalse(data.has("plugins"))
        assertFalse(data.toString().contains("zai-only-key"))
        assertEquals("Bearer zai-only-key", request.header("Authorization"))
        assertFalse(request.url.toString().contains("zai-only-key"))
    }

    @Test fun photoRequiresSeparateConsentAndExactFreeVisionModel() {
        val photo = WorkspaceChatGateway.Image("image/png", "cG5n")
        assertTrue(runCatching { WorkspaceZaiFree.request("zai-only-key", listOf(message), photo,
            WorkspaceZaiFree.DEFAULT_TEXT_MODEL, false) }.isFailure)
        val request = WorkspaceZaiFree.request("zai-only-key", listOf(message), photo,
            WorkspaceZaiFree.DEFAULT_TEXT_MODEL, true)
        assertEquals("glm-4.6v-flash", body(request).getString("model"))
        assertFalse(body(request).getBoolean("stream"))
        assertNull(request.header("Accept"))
        assertTrue(body(request).toString().contains("data:image/png;base64,cG5n"))
    }

    @Test fun chatClientDoesNotInheritTenSecondReadTimeoutAndNeverRetries() {
        assertEquals(15_000, WorkspaceZaiFree.client.connectTimeoutMillis)
        assertEquals(20_000, WorkspaceZaiFree.client.writeTimeoutMillis)
        assertEquals(40_000, WorkspaceZaiFree.client.readTimeoutMillis)
        assertEquals(45_000, WorkspaceZaiFree.client.callTimeoutMillis)
        assertFalse(WorkspaceZaiFree.client.retryOnConnectionFailure)
        assertFalse(WorkspaceZaiFree.client.followRedirects)
        assertFalse(WorkspaceZaiFree.client.followSslRedirects)
    }

    @Test fun streamingClientBoundsSilentGapsButAllowsHealthyLongerReply() {
        assertEquals(15_000, WorkspaceZaiFree.streamClient.connectTimeoutMillis)
        assertEquals(20_000, WorkspaceZaiFree.streamClient.writeTimeoutMillis)
        assertEquals(25_000, WorkspaceZaiFree.streamClient.readTimeoutMillis)
        assertEquals(90_000, WorkspaceZaiFree.streamClient.callTimeoutMillis)
        assertFalse(WorkspaceZaiFree.streamClient.retryOnConnectionFailure)
        assertFalse(WorkspaceZaiFree.streamClient.followRedirects)
        assertFalse(WorkspaceZaiFree.streamClient.followSslRedirects)
    }

    @Test fun streamingTimeoutDiagnosticMatchesStreamingClient() {
        val message = WorkspaceZaiFree.networkFailure(
            java.net.SocketTimeoutException("test"), streaming = true)
        assertTrue(message.contains("25s no-data gap"))
        assertTrue(message.contains("90s total"))
        assertTrue(message.contains("partial text"))
        assertFalse(message.contains("45s total"))
    }

    @Test fun businessCodeClassifiesKnown429WithoutLeakingProviderMessage() {
        fun failure(code: String): WorkspaceZaiFree.RateLimitException {
            val request = Request.Builder().url(WorkspaceZaiFree.ENDPOINT).build()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(429).message("Too Many Requests")
                .body(("{\"error\":{\"code\":\"$code\",\"message\":\"SECRET provider detail token=abc\"}}")
                    .toResponseBody("application/json".toMediaType()))
                .build()
            return runCatching { WorkspaceZaiFree.read(response) }.exceptionOrNull()
                as WorkspaceZaiFree.RateLimitException
        }
        val overload = failure("1305")
        assertEquals("1305", overload.businessCode)
        assertTrue(overload.message.orEmpty().contains("temporarily overloaded"))
        assertFalse(overload.message.orEmpty().contains("SECRET"))
        assertFalse(overload.message.orEmpty().contains("token=abc"))

        val usage = failure("1308")
        assertEquals("1308", usage.businessCode)
        assertTrue(usage.message.orEmpty().contains("usage-window limit"))
        val balance = failure("1113")
        assertTrue(balance.message.orEmpty().contains("balance/resource package"))
        assertTrue(balance.message.orEmpty().contains("No automatic retry"))
    }

    @Test fun unknownOrMalformedBusinessCodeIsSanitized() {
        val request = Request.Builder().url(WorkspaceZaiFree.ENDPOINT).build()
        val unknown = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(429).message("Too Many Requests")
            .body("{\"error\":{\"code\":\"1999\",\"message\":\"PRIVATE\"}}"
                .toResponseBody("application/json".toMediaType())).build()
        val ex = runCatching { WorkspaceZaiFree.read(unknown) }.exceptionOrNull()
            as WorkspaceZaiFree.RateLimitException
        assertEquals("1999", ex.businessCode)
        assertTrue(ex.message.orEmpty().contains("unrecognized Z.ai"))
        assertFalse(ex.message.orEmpty().contains("PRIVATE"))

        val malformed = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(429).message("Too Many Requests")
            .body("{\"error\":{\"code\":\"abc\",\"message\":\"PRIVATE2\"}}"
                .toResponseBody("application/json".toMediaType())).build()
        val ex2 = runCatching { WorkspaceZaiFree.read(malformed) }.exceptionOrNull()
            as WorkspaceZaiFree.RateLimitException
        assertNull(ex2.businessCode)
        assertFalse(ex2.message.orEmpty().contains("PRIVATE2"))
    }

    @Test fun rateLimitCarriesOnlyBoundedRetryAfterAndNeverClaimsDailyExhaustion() {
        val limited = WorkspaceZaiFree.rateLimitFailure("17")
        assertEquals(17_000L, limited.retryAfterMillis)
        assertTrue(limited.message.orEmpty().contains("Wait 17 seconds"))
        assertTrue(limited.message.orEmpty().contains("does not prove a daily quota"))
        assertTrue(limited.message.orEmpty().contains("No automatic retry"))
        assertNull(WorkspaceZaiFree.rateLimitFailure("301").retryAfterMillis)
        assertNull(WorkspaceZaiFree.rateLimitFailure("private").retryAfterMillis)
    }

    @Test fun zaiTimeoutDiagnosticMatchesActualBoundedClientWindows() {
        val message = WorkspaceZaiFree.networkFailure(java.net.SocketTimeoutException("test"))
        assertTrue(message.contains("40s read"))
        assertTrue(message.contains("45s total"))
        assertFalse(message.contains("35 seconds"))
        assertTrue(message.contains("No automatic retry"))
    }

    @Test fun websiteClientHasLongReadWindowButRemainsGloballyBounded() {
        assertEquals(20_000, WorkspaceZaiFree.websiteClient.connectTimeoutMillis)
        assertEquals(30_000, WorkspaceZaiFree.websiteClient.writeTimeoutMillis)
        assertEquals(70_000, WorkspaceZaiFree.websiteClient.readTimeoutMillis)
        assertEquals(80_000, WorkspaceZaiFree.websiteClient.callTimeoutMillis)
        assertFalse(WorkspaceZaiFree.websiteClient.retryOnConnectionFailure)
        assertFalse(WorkspaceZaiFree.websiteClient.followRedirects)
        assertFalse(WorkspaceZaiFree.websiteClient.followSslRedirects)
    }

    @Test fun codingSourceNeedsSeparateConsentAndNeverAddsAnotherProvider() {
        assertTrue(runCatching {
            WorkspaceZaiFree.editRequest("zai-only-key", "replace selected file",
                WorkspaceZaiFree.DEFAULT_TEXT_MODEL, sourceApproved = false)
        }.isFailure)
        val edit = WorkspaceZaiFree.editRequest("zai-only-key", "replace selected file",
            WorkspaceZaiFree.ALT_TEXT_MODEL, sourceApproved = true)
        val editBody = body(edit)
        assertEquals(WorkspaceZaiFree.ENDPOINT, edit.url.toString())
        assertEquals(WorkspaceZaiFree.ALT_TEXT_MODEL, editBody.getString("model"))
        assertFalse(editBody.has("provider"))
        assertFalse(editBody.has("plugins"))
        assertEquals(1, editBody.getJSONArray("messages").length())

        val snapshot = WorkspaceWebsiteGeneration.Snapshot("site", "task", "spec",
            "Build a dark landing page", mapOf(
                "index.html" to null, "style.css" to null, "script.js" to null))
        assertTrue(runCatching {
            WorkspaceZaiFree.websiteRequest("zai-only-key", snapshot,
                WorkspaceZaiFree.DEFAULT_TEXT_MODEL, sourceApproved = false)
        }.isFailure)
        val website = WorkspaceZaiFree.websiteRequest("zai-only-key", snapshot,
            WorkspaceZaiFree.DEFAULT_TEXT_MODEL, sourceApproved = true)
        val websiteBody = body(website)
        assertEquals(WorkspaceZaiFree.DEFAULT_TEXT_MODEL, websiteBody.getString("model"))
        assertFalse(websiteBody.has("provider"))
        assertFalse(websiteBody.has("plugins"))
        assertEquals(2, websiteBody.getJSONArray("messages").length())
        assertTrue(websiteBody.toString().contains("Build a dark landing page"))
        assertFalse(websiteBody.toString().contains("zai-only-key"))
    }

    @Test fun rateLimitAndIncompleteReplyFailWithoutEchoingSecrets() {
        val request = Request.Builder().url(WorkspaceZaiFree.ENDPOINT).build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(429).message("limit").body("secret-key echoed".toResponseBody()).build()
        val problem = runCatching { WorkspaceZaiFree.read(response) }.exceptionOrNull()
        assertNotNull(problem)
        assertFalse(problem!!.message.orEmpty().contains("secret-key"))
        assertTrue(problem.message.orEmpty().contains("429"))
        val partial = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("ok").body("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}".toResponseBody()).build()
        assertTrue(runCatching { WorkspaceZaiFree.read(partial) }.isFailure)
    }

    @Test fun selectionRequiresOptInAndImageConsent() {
        assertNull(WorkspaceFreeProviderSelection.choose(false, false, false, false, false,
            zaiApproved = false, zaiAvailable = true))
        assertEquals(WorkspaceChatGateway.Provider.ZAI_FREE,
            WorkspaceFreeProviderSelection.choose(false, false, false, false, false,
                zaiApproved = true, zaiAvailable = true))
        assertNull(WorkspaceFreeProviderSelection.choose(false, false, false, false, true,
            zaiApproved = true, zaiAvailable = true, hasImage = true))
        assertEquals(WorkspaceChatGateway.Provider.ZAI_FREE,
            WorkspaceFreeProviderSelection.choose(false, false, false, false, true,
                zaiApproved = true, zaiAvailable = true, zaiVisionApproved = true, hasImage = true))
    }

    @Test fun optedInZaiNeverSilentlyFallsBackToOtherCompany() {
        assertNull(WorkspaceFreeProviderSelection.choose(true, true, true, true, false,
            zaiApproved = true, zaiAvailable = false))
        assertNull(WorkspaceFreeProviderSelection.choose(true, true, true, true, true,
            zaiApproved = true, zaiAvailable = true, hasImage = true, zaiVisionApproved = false))
        assertEquals(WorkspaceChatGateway.Provider.ZAI_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, true,
                zaiApproved = true, zaiAvailable = true, hasImage = true, zaiVisionApproved = true))
    }
}
