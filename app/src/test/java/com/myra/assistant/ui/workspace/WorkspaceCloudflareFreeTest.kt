package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceCloudflareFreeTest {
    private val account = "0123456789abcdef0123456789abcdef"
    private val token = "test_not_a_real_api_token"
    private val message = WorkspaceConversationStore.Message("test", "user", "Say hello", 1L)

    private fun response(request: okhttp3.Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
        .body(body.toResponseBody()).build()

    @Test fun explicitFreeGateAndAccountIdRejectBadValues() {
        assertFalse(WorkspaceCloudflareFree.configured(false, token, account))
        assertFalse(WorkspaceCloudflareFree.configured(true, token, "not-an-account"))
        assertFalse(WorkspaceCloudflareFree.validToken("bad token"))
        assertTrue(WorkspaceCloudflareFree.configured(true, token, account))
    }

    @Test fun directChatNeverUsesGatewayOrPaidModel() {
        val req = WorkspaceCloudflareFree.chatRequest(token, account, listOf(message))
        assertEquals("api.cloudflare.com", req.url.host)
        assertTrue(req.url.toString().endsWith("/ai/run/@cf/zai-org/glm-4.7-flash"))
        val buffer = Buffer()
        requireNotNull(req.body).writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        assertEquals(false, json.getBoolean("stream"))
        assertEquals(false, json.getBoolean("store"))
        assertEquals(2048, json.getInt("max_completion_tokens"))
        assertEquals("Say hello", json.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(json.has("provider"))
        assertFalse(json.has("plugins"))
        assertFalse(json.has("model"))
        assertFalse(json.has("gateway"))
        assertFalse(json.has("max_tokens"))
    }

    @Test fun directChatReadsCloudflareWrappedCompletion() {
        val req = WorkspaceCloudflareFree.chatRequest(token, account, listOf(message))
        val data = """{"success":true,"result":{"choices":[{"finish_reason":"stop","message":{"content":"CF-API-OK"}}]}}"""
        assertEquals("CF-API-OK", WorkspaceCloudflareFree.readChat(response(req, 200, data)))
    }

    @Test fun rejectedAndIncompleteResponsesDoNotExposeRawProviderText() {
        val req = WorkspaceCloudflareFree.chatRequest(token, account, listOf(message))
        val denied = runCatching { WorkspaceCloudflareFree.readChat(response(req, 429,
            """{"errors":[{"code":3036,"message":"PRIVATE_KEY_SENTINEL"}]}""")) }.exceptionOrNull()
        assertNotNull(denied)
        assertTrue(denied!!.message.orEmpty().contains("daily neurons exhausted"))
        assertFalse(denied.message.orEmpty().contains("PRIVATE_KEY_SENTINEL"))
        val partial = runCatching { WorkspaceCloudflareFree.readChat(response(req, 200,
            """{"result":{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}}"""))
        }.exceptionOrNull()
        assertNotNull(partial)
        assertTrue(partial!!.message.orEmpty().contains("incomplete"))
    }

    @Test fun websiteUsesCanonicalThreeFilesAndLocalValidator() {
        val snapshot = WorkspaceWebsiteGeneration.Snapshot("site", "task", "spec", "Build a counter",
            WorkspaceWebsiteGeneration.PATHS.associateWith { null })
        val req = WorkspaceCloudflareFree.websiteRequest(token, account, snapshot)
        val buffer = Buffer()
        requireNotNull(req.body).writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        assertEquals(7000, json.getInt("max_completion_tokens"))
        assertFalse(json.has("provider"))
        assertTrue(json.getJSONArray("messages").getJSONObject(1).getString("content")
            .contains("existingFiles"))
        val bad = runCatching { WorkspaceCloudflareFree.readWebsite(response(req, 200,
            """{"success":true,"result":{"choices":[{"finish_reason":"stop","message":{"content":"{\\\"files\\\":{\\\"index.html\\\":\\\"missing CSS and JS\\\"}}"}}]}}"""))
        }.exceptionOrNull()
        assertNotNull(bad)
    }
}
