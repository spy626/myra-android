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

    private fun failure(request: okhttp3.Request, json: String): String =
        runCatching { WorkspaceCloudflareFree.readChat(response(request, 200, json)) }
            .exceptionOrNull()?.message ?: error("Expected safe Cloudflare failure")

    @Test fun explicitFreeGateAndAccountIdRejectBadValues() {
        assertFalse(WorkspaceCloudflareFree.configured(false, token, account))
        assertFalse(WorkspaceCloudflareFree.configured(true, token, "not-an-account"))
        assertFalse(WorkspaceCloudflareFree.validToken("bad token"))
        assertTrue(WorkspaceCloudflareFree.configured(true, token, account))
    }

    @Test fun directChatNeverUsesGatewayOrPaidModelAndDisablesHiddenThinking() {
        val req = WorkspaceCloudflareFree.chatRequest(token, account, listOf(message))
        assertEquals("api.cloudflare.com", req.url.host)
        assertTrue(req.url.toString().endsWith("/ai/run/@cf/zai-org/glm-4.7-flash"))
        val buffer = Buffer()
        requireNotNull(req.body).writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        assertEquals(false, json.getBoolean("stream"))
        assertEquals(false, json.getBoolean("store"))
        assertEquals(2048, json.getInt("max_completion_tokens"))
        assertEquals(JSONObject.NULL, json.opt("reasoning_effort"))
        assertFalse(json.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        assertEquals("Say hello", json.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(json.has("provider"))
        assertFalse(json.has("plugins"))
        assertFalse(json.has("model"))
        assertFalse(json.has("gateway"))
        assertFalse(json.has("max_tokens"))
    }

    @Test fun directChatReadsCloudflareWrappedCompletionAndTextParts() {
        val req = WorkspaceCloudflareFree.chatRequest(token, account, listOf(message))
        val data = """{"success":true,"result":{"choices":[{"finish_reason":"stop","message":{"content":"CF-API-OK"}}]}}"""
        assertEquals("CF-API-OK", WorkspaceCloudflareFree.readChat(response(req, 200, data)))
        val parts = """{"result":{"choices":[{"finish_reason":"stop","message":{"content":[{"type":"text","text":"Hello"},{"type":"output_text","text":" bro"}],"reasoning_content":"PRIVATE_REASON"}}]}}"""
        assertEquals("Hello bro", WorkspaceCloudflareFree.readChat(response(req, 200, parts)))
        val legacy = """{"success":true,"result":{"response":"Legacy visible reply"}}"""
        assertEquals("Legacy visible reply", WorkspaceCloudflareFree.readChat(response(req, 200, legacy)))
    }

    @Test fun thinkingOnlyOrEmptyResponsesNeverBecomeVisibleReplies() {
        val req = WorkspaceCloudflareFree.chatRequest(token, account, listOf(message))
        val thoughtOnly = """{"success":true,"result":{"choices":[{"finish_reason":"stop","message":{"content":"","reasoning_content":"PRIVATE_REASON_ONLY","tool_calls":[]}}]}}"""
        val empty = failure(req, thoughtOnly)
        assertTrue(empty.contains("content_empty"))
        assertFalse(empty.contains("PRIVATE_REASON_ONLY"))
        val missing = failure(req, """{"result":{"choices":[{"finish_reason":"stop","message":{"content":null,"reasoning_content":"PRIVATE_REASON_ONLY"}}]}}""")
        assertTrue(missing.contains("content_missing"))
        assertFalse(missing.contains("PRIVATE_REASON_ONLY"))
        val mixed = failure(req, """{"result":{"choices":[{"finish_reason":"stop","message":{"content":[{"type":"text","text":"Visible"},{"type":"reasoning","text":"PRIVATE_REASON_ONLY"}]}}]}}""")
        assertTrue(mixed.contains("content_parts_missing_or_invalid"))
        assertFalse(mixed.contains("PRIVATE_REASON_ONLY"))
    }

    @Test fun rejectedAndIncompleteResponsesDoNotExposeRawProviderText() {
        val req = WorkspaceCloudflareFree.chatRequest(token, account, listOf(message))
        val denied = runCatching { WorkspaceCloudflareFree.readChat(response(req, 429,
            """{"errors":[{"code":3036,"message":"PRIVATE_KEY_SENTINEL"}]}""")) }.exceptionOrNull()
        assertNotNull(denied)
        assertTrue(denied!!.message.orEmpty().contains("daily neurons exhausted"))
        assertFalse(denied.message.orEmpty().contains("PRIVATE_KEY_SENTINEL"))
        val partial = failure(req,
            """{"result":{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}}""")
        assertTrue(partial.contains("output_limit"))
        assertFalse(partial.contains("partial"))
        val malformed = failure(req, """{"result":{"choices":[]},"private":"PRIVATE_KEY_SENTINEL"}""")
        assertTrue(malformed.contains("choices_missing"))
        assertFalse(malformed.contains("PRIVATE_KEY_SENTINEL"))
        val badJson = failure(req, "PRIVATE_KEY_SENTINEL")
        assertTrue(badJson.contains("invalid_json"))
        assertFalse(badJson.contains("PRIVATE_KEY_SENTINEL"))
    }

    @Test fun websiteUsesCanonicalThreeFilesAndLocalValidator() {
        val snapshot = WorkspaceWebsiteGeneration.Snapshot("site", "task", "spec", "Build a counter",
            WorkspaceWebsiteGeneration.PATHS.associateWith { null })
        val req = WorkspaceCloudflareFree.websiteRequest(token, account, snapshot)
        val buffer = Buffer()
        requireNotNull(req.body).writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        assertEquals(7000, json.getInt("max_completion_tokens"))
        assertFalse(json.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        assertFalse(json.has("provider"))
        assertTrue(json.getJSONArray("messages").getJSONObject(1).getString("content")
            .contains("existingFiles"))
        val bad = runCatching { WorkspaceCloudflareFree.readWebsite(response(req, 200,
            """{"success":true,"result":{"choices":[{"finish_reason":"stop","message":{"content":"{\\\"files\\\":{\\\"index.html\\\":\\\"missing CSS and JS\\\"}}"}}]}}"""))
        }.exceptionOrNull()
        assertNotNull(bad)
    }
}
