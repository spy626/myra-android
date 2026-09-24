package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceProviderRegistryTest {
    private val r = WorkspaceProviderRegistry

    @Test fun capabilitiesKeepChatAndCodingBoundariesExplicit() {
        assertTrue(r.supports(r.Id.OPENROUTER_FREE, r.TaskKind.CHAT_ATTACHMENT))
        assertTrue(r.allowsAttachments(r.Id.OPENROUTER_FREE))
        assertTrue(r.supports(r.Id.GROQ_FREE, r.TaskKind.CHAT_TEXT))
        assertFalse(r.supports(r.Id.GROQ_FREE, r.TaskKind.CHAT_ATTACHMENT))
        assertFalse(r.allowsAttachments(r.Id.GROQ_FREE))
        assertTrue(r.supports(r.Id.LLM7_FREE, r.TaskKind.CHAT_TEXT))
        assertFalse(r.allowsSource(r.Id.LLM7_FREE))
        assertFalse(r.supports(r.Id.LLM7_FREE, r.TaskKind.CODE_EDIT))
        assertTrue(r.supports(r.Id.XKIRO_FREE, r.TaskKind.CODE_EDIT))
        assertTrue(r.supports(r.Id.XKIRO_FREE, r.TaskKind.WEBSITE_BUILD))
        assertFalse(r.supports(r.Id.XKIRO_FREE, r.TaskKind.CHAT_TEXT))
        assertTrue(r.supports(r.Id.ZAI_FREE, r.TaskKind.CODE_EDIT))
        assertFalse(r.supports(r.Id.ZAI_FREE, r.TaskKind.CHAT_TEXT))
    }

    @Test fun freeAssuranceDoesNotPretendEveryProviderHasSameGuarantee() {
        assertEquals(r.FreeAssurance.API_ZERO_PRICE_CEILING,
            r.capability(r.Id.OPENROUTER_FREE).freeAssurance)
        assertEquals(r.FreeAssurance.USER_ATTESTED_FREE_ACCOUNT,
            r.capability(r.Id.GROQ_FREE).freeAssurance)
        assertEquals(r.FreeAssurance.PREFLIGHT_ZERO_PRICE_AND_QUOTA,
            r.capability(r.Id.XKIRO_FREE).freeAssurance)
        assertEquals(r.FreeAssurance.NO_PAID_FALLBACK_ONLY,
            r.capability(r.Id.ZAI_FREE).freeAssurance)
    }

    @Test fun budgetsAreBoundedAndTaskSpecific() {
        assertEquals(WorkspaceGroqFree.MAX_PROMPT_CHARS,
            r.budget(r.Id.GROQ_FREE, r.TaskKind.CHAT_TEXT)?.maxPromptChars)
        assertEquals(3_500,
            r.budget(r.Id.XKIRO_FREE, r.TaskKind.CODE_EDIT)?.maxOutputTokens)
        assertEquals(7_000,
            r.budget(r.Id.XKIRO_FREE, r.TaskKind.WEBSITE_BUILD)?.maxOutputTokens)
        assertNull(r.budget(r.Id.LLM7_FREE, r.TaskKind.CODE_EDIT))
    }

    @Test fun fallbackEligibilityMatchesExistingDefiniteHttpPolicy() {
        listOf(429, 502, 503, 504).forEach {
            assertTrue(r.definitiveFallbackAllowed(r.Id.XKIRO_FREE, it))
            assertTrue(r.definitiveFallbackAllowed(r.Id.GROQ_FREE, it))
        }
        assertTrue(r.definitiveFallbackAllowed(r.Id.OPENROUTER_FREE, 404))
        listOf(400, 401, 402, 403, 408, 413).forEach {
            assertFalse(r.definitiveFallbackAllowed(r.Id.XKIRO_FREE, it))
            assertFalse(r.definitiveFallbackAllowed(r.Id.GROQ_FREE, it))
        }
        assertFalse(r.definitiveFallbackAllowed(r.Id.ZAI_FREE, 429))
        assertFalse(r.definitiveFallbackAllowed(r.Id.LLM7_FREE, 503))
    }

    @Test fun healthClassificationKeepsPaymentAndUncertainOutcomesTerminal() {
        assertEquals(r.HealthState.ACCESS_REFUSED,
            r.classifyHttp(r.Id.OPENROUTER_FREE, 401).state)
        assertEquals(r.HealthState.PAYMENT_REQUIRED,
            r.classifyHttp(r.Id.OPENROUTER_FREE, 402).state)
        val limited = r.classifyHttp(r.Id.XKIRO_FREE, 429, retryAfterSeconds = 15)
        assertEquals(r.HealthState.COOLDOWN, limited.state)
        assertEquals(15_000L, limited.retryAfterMillis)
        assertTrue(limited.definitiveHttp)
        val timeout = r.classifyHttp(r.Id.ZAI_FREE, 408)
        assertEquals(r.HealthState.UNCERTAIN_OUTCOME, timeout.state)
        assertFalse(timeout.definitiveHttp)
        assertFalse(r.uncertainNetworkFailure().definitiveHttp)
    }
}
