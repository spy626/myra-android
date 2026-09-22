package com.myra.assistant.ui.workspace

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
        assertTrue(body(request).toString().contains("data:image/png;base64,cG5n"))
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
