package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceProviderRegistryTest {
    private val r = WorkspaceProviderRegistry

    @Test fun capabilitiesKeepChatAndCodingBoundariesExplicit() {
        assertTrue(r.supports(WorkspaceProviderRegistry.Id.OPENROUTER_FREE, WorkspaceProviderRegistry.TaskKind.CHAT_ATTACHMENT))
        assertTrue(r.allowsAttachments(WorkspaceProviderRegistry.Id.OPENROUTER_FREE))
        assertTrue(r.supports(WorkspaceProviderRegistry.Id.GROQ_FREE, WorkspaceProviderRegistry.TaskKind.CHAT_TEXT))
        assertFalse(r.supports(WorkspaceProviderRegistry.Id.GROQ_FREE, WorkspaceProviderRegistry.TaskKind.CHAT_ATTACHMENT))
        assertFalse(r.allowsAttachments(WorkspaceProviderRegistry.Id.GROQ_FREE))
        assertTrue(r.supports(WorkspaceProviderRegistry.Id.LLM7_FREE, WorkspaceProviderRegistry.TaskKind.CHAT_TEXT))
        assertFalse(r.allowsSource(WorkspaceProviderRegistry.Id.LLM7_FREE))
        assertFalse(r.supports(WorkspaceProviderRegistry.Id.LLM7_FREE, WorkspaceProviderRegistry.TaskKind.CODE_EDIT))
        assertTrue(r.supports(WorkspaceProviderRegistry.Id.XKIRO_FREE, WorkspaceProviderRegistry.TaskKind.CODE_EDIT))
        assertTrue(r.supports(WorkspaceProviderRegistry.Id.XKIRO_FREE, WorkspaceProviderRegistry.TaskKind.WEBSITE_BUILD))
        assertFalse(r.supports(WorkspaceProviderRegistry.Id.XKIRO_FREE, WorkspaceProviderRegistry.TaskKind.CHAT_TEXT))
        assertTrue(r.supports(WorkspaceProviderRegistry.Id.ZAI_FREE, WorkspaceProviderRegistry.TaskKind.CODE_EDIT))
        assertFalse(r.supports(WorkspaceProviderRegistry.Id.ZAI_FREE, WorkspaceProviderRegistry.TaskKind.CHAT_TEXT))
    }

    @Test fun freeAssuranceDoesNotPretendEveryProviderHasSameGuarantee() {
        assertEquals(WorkspaceProviderRegistry.FreeAssurance.API_ZERO_PRICE_CEILING,
            r.capability(WorkspaceProviderRegistry.Id.OPENROUTER_FREE).freeAssurance)
        assertEquals(WorkspaceProviderRegistry.FreeAssurance.USER_ATTESTED_FREE_ACCOUNT,
            r.capability(WorkspaceProviderRegistry.Id.GROQ_FREE).freeAssurance)
        assertEquals(WorkspaceProviderRegistry.FreeAssurance.PREFLIGHT_ZERO_PRICE_AND_QUOTA,
            r.capability(WorkspaceProviderRegistry.Id.XKIRO_FREE).freeAssurance)
        assertEquals(WorkspaceProviderRegistry.FreeAssurance.NO_PAID_FALLBACK_ONLY,
            r.capability(WorkspaceProviderRegistry.Id.ZAI_FREE).freeAssurance)
    }

    @Test fun budgetsAreBoundedAndTaskSpecific() {
        assertEquals(WorkspaceGroqFree.MAX_PROMPT_CHARS,
            r.budget(WorkspaceProviderRegistry.Id.GROQ_FREE, WorkspaceProviderRegistry.TaskKind.CHAT_TEXT)?.maxPromptChars)
        assertEquals(3_500,
            r.budget(WorkspaceProviderRegistry.Id.XKIRO_FREE, WorkspaceProviderRegistry.TaskKind.CODE_EDIT)?.maxOutputTokens)
        assertEquals(7_000,
            r.budget(WorkspaceProviderRegistry.Id.XKIRO_FREE, WorkspaceProviderRegistry.TaskKind.WEBSITE_BUILD)?.maxOutputTokens)
        assertNull(r.budget(WorkspaceProviderRegistry.Id.LLM7_FREE, WorkspaceProviderRegistry.TaskKind.CODE_EDIT))
    }

    @Test fun fallbackEligibilityMatchesExistingDefiniteHttpPolicy() {
        listOf(429, 502, 503, 504).forEach {
            assertTrue(r.definitiveFallbackAllowed(WorkspaceProviderRegistry.Id.XKIRO_FREE, it))
            assertTrue(r.definitiveFallbackAllowed(WorkspaceProviderRegistry.Id.GROQ_FREE, it))
        }
        assertTrue(r.definitiveFallbackAllowed(WorkspaceProviderRegistry.Id.OPENROUTER_FREE, 404))
        listOf(400, 401, 402, 403, 408, 413).forEach {
            assertFalse(r.definitiveFallbackAllowed(WorkspaceProviderRegistry.Id.XKIRO_FREE, it))
            assertFalse(r.definitiveFallbackAllowed(WorkspaceProviderRegistry.Id.GROQ_FREE, it))
        }
        assertFalse(r.definitiveFallbackAllowed(WorkspaceProviderRegistry.Id.ZAI_FREE, 429))
        assertFalse(r.definitiveFallbackAllowed(WorkspaceProviderRegistry.Id.LLM7_FREE, 503))
    }

    @Test fun endpointsAndWebsiteProvidersMapToCanonicalIds() {
        assertEquals(WorkspaceProviderRegistry.Id.OPENROUTER_FREE,
            r.idForEndpoint(WorkspaceFreeAiSuggestion.ENDPOINT))
        assertEquals(WorkspaceProviderRegistry.Id.XKIRO_FREE,
            r.idForEndpoint(WorkspaceXKiroFree.ENDPOINT))
        assertEquals(WorkspaceProviderRegistry.Id.ZAI_FREE,
            r.id(WorkspaceWebsiteRoute.Provider.ZAI))
        assertNull(r.idForEndpoint("https://example.invalid/v1/chat/completions"))
    }

    @Test fun healthClassificationKeepsPaymentAndUncertainOutcomesTerminal() {
        assertEquals(WorkspaceProviderRegistry.HealthState.ACCESS_REFUSED,
            r.classifyHttp(WorkspaceProviderRegistry.Id.OPENROUTER_FREE, 401).state)
        assertEquals(WorkspaceProviderRegistry.HealthState.PAYMENT_REQUIRED,
            r.classifyHttp(WorkspaceProviderRegistry.Id.OPENROUTER_FREE, 402).state)
        val limited = r.classifyHttp(WorkspaceProviderRegistry.Id.XKIRO_FREE, 429, retryAfterSeconds = 15)
        assertEquals(WorkspaceProviderRegistry.HealthState.COOLDOWN, limited.state)
        assertEquals(15_000L, limited.retryAfterMillis)
        assertTrue(limited.definitiveHttp)
        val timeout = r.classifyHttp(WorkspaceProviderRegistry.Id.ZAI_FREE, 408)
        assertEquals(WorkspaceProviderRegistry.HealthState.UNCERTAIN_OUTCOME, timeout.state)
        assertFalse(timeout.definitiveHttp)
        assertFalse(r.uncertainNetworkFailure().definitiveHttp)
    }
}
