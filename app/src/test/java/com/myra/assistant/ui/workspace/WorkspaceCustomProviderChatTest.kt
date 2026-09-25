package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceCustomProviderChatTest {
    private fun profile() = WorkspaceCustomProviderProfile.validate(
        WorkspaceCustomProviderProfile.userDraft(
            WorkspaceCustomProviderStore.DEFAULT_PROFILE_ID,
            "Manual", "https://api.example.com/v1", "vendor/model"))

    private fun message(role: String, text: String, id: String) =
        WorkspaceConversationStore.Message(id, role, text, 1L)

    @Test fun requestUsesSameChatOnlyAndNoOpenRouterSpecificFallbackFields() {
        val messages = listOf(
            message("user", "Hello", "u1"),
            message("assistant", "Hi", "a1"),
            message("user", "Keep it short", "u2"),
        )
        val request = WorkspaceCustomProviderChat.request(profile(), "secret", messages)
        assertEquals("https://api.example.com/v1/chat/completions", request.url.toString())
        assertEquals("Bearer secret", request.header("Authorization"))
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        val raw = buffer.readUtf8()
        val json = JSONObject(raw)
        assertEquals("vendor/model", json.getString("model"))
        assertTrue(raw.contains("Keep it short"))
        assertFalse(json.has("provider"))
        assertFalse(json.has("plugins"))
        assertFalse(raw.contains("secret"))
    }

    @Test fun oneTurnInstructionsAreIncludedWithoutPersistingOrLeakingKey() {
        val request = WorkspaceCustomProviderChat.request(
            profile(), "secret", listOf(message("user", "Task", "u1")),
            extraSystemInstructions = "SKILL-ONE-TURN")
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        val raw = buffer.readUtf8()
        val json = JSONObject(raw)
        assertTrue(json.getJSONArray("messages").getJSONObject(0)
            .getString("content").contains("SKILL-ONE-TURN"))
        assertFalse(raw.contains("secret"))
    }

    @Test fun responseParserAcceptsCompleteTextAndHidesRawFailureBody() {
        val request = Request.Builder().url(profile().chatCompletionsUrl).build()
        val ok = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK")
            .body("""{"choices":[{"finish_reason":"stop","message":{"content":"Hello bro"}}]}"""
                .toResponseBody()).build()
        assertEquals("Hello bro", WorkspaceCustomProviderChat.read(ok))

        val denied = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(402).message("Pay")
            .body("TOP SECRET PROVIDER BODY".toResponseBody()).build()
        val failure = runCatching { WorkspaceCustomProviderChat.read(denied) }
            .exceptionOrNull()?.message.orEmpty()
        assertTrue(failure.contains("not attempt payment"))
        assertFalse(failure.contains("TOP SECRET"))
    }

    @Test fun budgetStopsLocallyBeforeAnyRequestCanBeBuilt() {
        val tiny = profile().copy(maxPromptChars = 1_000)
        val huge = listOf(message("user", "x".repeat(1_001), "u1"))
        assertTrue(runCatching {
            WorkspaceCustomProviderChat.request(tiny, "", huge)
        }.isFailure)
    }
}
