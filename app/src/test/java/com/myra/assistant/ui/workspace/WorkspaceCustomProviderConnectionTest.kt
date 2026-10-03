package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class WorkspaceCustomProviderConnectionTest {
    private fun profile() = WorkspaceCustomProviderProfile.validate(
        WorkspaceCustomProviderProfile.userDraft(
            WorkspaceCustomProviderStore.DEFAULT_PROFILE_ID,
            "Test", "https://api.example.com/v1", "vendor/model"))

    @Test fun syntheticRequestContainsNoUserSourceAndKeepsKeyOutOfBodyAndUrl() {
        val request = WorkspaceCustomProviderConnection.request(profile(), "secret-key")
        assertEquals("https://api.example.com/v1/chat/completions", request.url.toString())
        assertEquals("Bearer secret-key", request.header("Authorization"))
        assertFalse(request.url.toString().contains("secret-key"))
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        assertEquals("vendor/model", json.getString("model"))
        assertEquals(WorkspaceCustomProviderProfile.SYNTHETIC_CONNECTION_TEST_TEXT,
            json.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(json.toString().contains("secret-key"))
    }

    @Test fun xApiKeyModeUsesOnlyDedicatedHeader() {
        val p = WorkspaceCustomProviderProfile.validate(
            WorkspaceCustomProviderProfile.userDraft(
                WorkspaceCustomProviderStore.DEFAULT_PROFILE_ID,
                "Test", "https://api.example.com/v1", "model")
                .copy(authMode = WorkspaceCustomProviderProfile.AuthMode.X_API_KEY))
        val request = WorkspaceCustomProviderConnection.request(p, "abc")
        assertEquals("abc", request.header("X-API-Key"))
        assertNull(request.header("Authorization"))
    }

    @Test fun clientNeverRetriesOrFollowsRedirects() {
        val client = WorkspaceCustomProviderConnection.client(profile())
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test fun parserAcceptsBoundedOpenAiReplyAndNeverEchoesProviderBodyOnError() {
        val request = Request.Builder().url(profile().chatCompletionsUrl).build()
        val ok = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK")
            .body("""{"choices":[{"message":{"content":"OK"}}]}""".toResponseBody()).build()
        assertTrue(WorkspaceCustomProviderConnection.read(ok).startsWith("Connection successful"))

        val denied = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(401).message("Denied")
            .body("SECRET PROVIDER BODY".toResponseBody()).build()
        val message = runCatching { WorkspaceCustomProviderConnection.read(denied) }
            .exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("access refused"))
        assertFalse(message.contains("SECRET PROVIDER BODY"))
    }

    @Test fun publicDnsGuardRecognizesLocalAndPrivateAddresses() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.1.2", "192.168.1.2", "169.254.1.2", "::1")
            .forEach {
                assertTrue(it, WorkspaceCustomProviderConnection.unsafePublicResolution(
                    InetAddress.getByName(it)))
            }
        assertFalse(WorkspaceCustomProviderConnection.unsafePublicResolution(
            InetAddress.getByName("8.8.8.8")))
    }
}
