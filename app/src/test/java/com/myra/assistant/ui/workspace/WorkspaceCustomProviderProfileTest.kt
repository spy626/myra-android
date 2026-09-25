package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceCustomProviderProfileTest {
    private val p = WorkspaceCustomProviderProfile

    @Test fun validInternetProfileIsHttpsManualOnlyAndBuildsExactChatEndpoint() {
        val validated = p.validate(p.userDraft(
            id = "my_gateway",
            displayName = "My Gateway",
            baseUrl = "https://api.example.com/openai/v1/",
            modelId = "vendor/model-free",
        ))
        assertEquals("https://api.example.com/openai/v1", validated.baseUrl)
        assertEquals("https://api.example.com/openai/v1/chat/completions",
            validated.chatCompletionsUrl)
        assertEquals(WorkspaceCustomProviderProfile.CostState.UNVERIFIED_COST, validated.costState)
        assertFalse(validated.automaticRouting)
        assertFalse(validated.sourceAllowed)
        assertTrue(validated.encryptedKeySlot.startsWith("custom_provider_api_key_"))
    }

    @Test fun publicInternetHttpAndPrivateHostsNeedExplicitLocalMode() {
        assertTrue(runCatching { p.validate(p.userDraft(
            "bad_http", "Bad", "http://api.example.com/v1", "model")) }.isFailure)
        assertTrue(runCatching { p.validate(p.userDraft(
            "local_wrong_mode", "Local", "https://127.0.0.1:8000/v1", "model")) }.isFailure)

        val local = p.validate(p.userDraft(
            "local_ok", "Local", "http://192.168.1.20:8080/v1", "model", localEndpoint = true))
        assertEquals("http://192.168.1.20:8080/v1", local.baseUrl)
        assertTrue(local.localEndpoint)

        assertTrue(runCatching { p.validate(p.userDraft(
            "fake_local", "Fake", "http://example.com/v1", "model", localEndpoint = true)) }.isFailure)
    }

    @Test fun rejectsCredentialsQueriesFragmentsAndFullCompletionEndpoint() {
        listOf(
            "https://user:pass@example.com/v1",
            "https://example.com/v1?api_key=secret",
            "https://example.com/v1#token",
            "https://example.com/v1/chat/completions",
            "ftp://example.com/v1"
        ).forEach { url ->
            assertTrue("Unsafe URL accepted: $url", runCatching {
                p.validate(p.userDraft("unsafe", "Unsafe", url, "model"))
            }.isFailure)
        }
    }

    @Test fun localHostClassifierCoversCommonPrivateRanges() {
        listOf("localhost", "api.local", "10.1.2.3", "127.0.0.1",
            "172.16.0.1", "172.31.255.255", "192.168.4.2", "169.254.1.2", "::1", "fd00::1")
            .forEach { assertTrue("Expected local: $it", p.isLocalHost(it)) }
        listOf("example.com", "8.8.8.8", "172.32.0.1", "192.0.2.1")
            .forEach { assertFalse("Expected public: $it", p.isLocalHost(it)) }
    }

    @Test fun permissionsAndBudgetsAreFailClosed() {
        assertTrue(runCatching { p.validate(p.userDraft(
            "attachment_bad", "Bad", "https://example.com/v1", "model").copy(
                attachmentsAllowed = true)) }.isFailure)
        assertTrue(runCatching { p.validate(p.userDraft(
            "source_bad", "Bad", "https://example.com/v1", "model").copy(
                sourceAllowed = true)) }.isFailure)

        val code = p.validate(p.userDraft(
            "code_ok", "Code", "https://example.com/v1", "model").copy(
                tasks = setOf(WorkspaceProviderRegistry.TaskKind.CODE_EDIT),
                sourceAllowed = true,
                maxPromptChars = 12_000,
                maxOutputTokens = 2_048))
        assertTrue(code.sourceAllowed)
    }

    @Test fun unverifiedCostCannotEnterAutomaticRouting() {
        val base = p.userDraft("manual", "Manual", "https://example.com/v1", "model")
        assertTrue(runCatching { p.validate(base.copy(automaticRouting = true)) }.isFailure)
        val verified = p.validate(base.copy(
            costState = WorkspaceCustomProviderProfile.CostState.VERIFIED_ZERO_COST,
            automaticRouting = true))
        assertTrue(verified.automaticRouting)
    }

    @Test fun idsNamesAndTimeoutsAreBounded() {
        assertTrue(runCatching { p.validate(p.userDraft(
            "Bad ID!", "Name", "https://example.com/v1", "model")) }.isFailure)
        assertTrue(runCatching { p.validate(p.userDraft(
            "ok", "", "https://example.com/v1", "model")) }.isFailure)
        assertTrue(runCatching { p.validate(p.userDraft(
            "ok", "Name", "https://example.com/v1", "model").copy(timeoutSeconds = 121)) }.isFailure)
    }
}
