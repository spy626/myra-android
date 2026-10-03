package com.myra.assistant.ui.workspace

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WorkspaceProviderSessionHealthTest {
    private val h = WorkspaceProviderSessionHealth

    @After fun clean() = h.clearForTests()

    @Test fun rateLimitUsesServerDelayAndBlocksOnlyUntilExpiry() {
        h.recordHttp(WorkspaceProviderRegistry.Id.XKIRO_FREE, 429, "15", nowMs = 1_000L)
        assertFalse(h.canSend(WorkspaceProviderRegistry.Id.XKIRO_FREE, nowMs = 10_000L))
        assertEquals(6_000L, h.remainingMillis(WorkspaceProviderRegistry.Id.XKIRO_FREE, nowMs = 10_000L))
        assertTrue(h.canSend(WorkspaceProviderRegistry.Id.XKIRO_FREE, nowMs = 16_000L))
        assertNull(h.snapshot(WorkspaceProviderRegistry.Id.XKIRO_FREE, nowMs = 16_000L))
    }

    @Test fun rateLimitWithoutHeaderGetsBoundedDefaultCooldown() {
        h.recordHttp(WorkspaceProviderRegistry.Id.OPENROUTER_FREE, 429, nowMs = 5_000L)
        assertEquals(60_000L, h.remainingMillis(WorkspaceProviderRegistry.Id.OPENROUTER_FREE, nowMs = 5_000L))
        assertTrue(h.cooldownMessage(WorkspaceProviderRegistry.Id.OPENROUTER_FREE, nowMs = 5_000L)
            .contains("OpenRouter Free"))
    }

    @Test fun temporaryOutageGetsShortCooldownButRequestErrorsDoNot() {
        h.recordHttp(WorkspaceProviderRegistry.Id.GROQ_FREE, 503, nowMs = 1_000L)
        assertFalse(h.canSend(WorkspaceProviderRegistry.Id.GROQ_FREE, nowMs = 2_000L))
        assertTrue(h.canSend(WorkspaceProviderRegistry.Id.GROQ_FREE, nowMs = 21_000L))

        h.recordHttp(WorkspaceProviderRegistry.Id.GROQ_FREE, 400, nowMs = 30_000L)
        assertTrue(h.canSend(WorkspaceProviderRegistry.Id.GROQ_FREE, nowMs = 30_000L))
        assertEquals(WorkspaceProviderRegistry.HealthState.REQUEST_REJECTED,
            h.snapshot(WorkspaceProviderRegistry.Id.GROQ_FREE, nowMs = 30_000L)?.state)
    }

    @Test fun uncertainNetworkFailureNeverCreatesCooldown() {
        h.recordUncertainNetworkFailure(WorkspaceProviderRegistry.Id.ZAI_FREE)
        assertTrue(h.canSend(WorkspaceProviderRegistry.Id.ZAI_FREE, nowMs = 1_000L))
        assertEquals(WorkspaceProviderRegistry.HealthState.UNCERTAIN_OUTCOME,
            h.snapshot(WorkspaceProviderRegistry.Id.ZAI_FREE, nowMs = 1_000L)?.state)
        assertEquals(0L, h.remainingMillis(WorkspaceProviderRegistry.Id.ZAI_FREE, nowMs = 1_000L))
    }

    @Test fun uncertainFailureDoesNotEraseActiveDefiniteCooldown() {
        h.recordHttp(WorkspaceProviderRegistry.Id.XKIRO_FREE, 429, "20", nowMs = 1_000L)
        h.recordUncertainNetworkFailure(WorkspaceProviderRegistry.Id.XKIRO_FREE, nowMs = 2_000L)
        assertFalse(h.canSend(WorkspaceProviderRegistry.Id.XKIRO_FREE, nowMs = 2_000L))
        assertEquals(WorkspaceProviderRegistry.HealthState.COOLDOWN,
            h.snapshot(WorkspaceProviderRegistry.Id.XKIRO_FREE, nowMs = 2_000L)?.state)
    }

    @Test fun onlyRateLimitAndTemporaryOutageBlockNewSends() {
        h.recordHttp(WorkspaceProviderRegistry.Id.OPENROUTER_FREE, 401, nowMs = 1_000L)
        assertTrue(h.canSend(WorkspaceProviderRegistry.Id.OPENROUTER_FREE, nowMs = 1_000L))
        h.recordHttp(WorkspaceProviderRegistry.Id.OPENROUTER_FREE, 402, nowMs = 2_000L)
        assertTrue(h.canSend(WorkspaceProviderRegistry.Id.OPENROUTER_FREE, nowMs = 2_000L))
        h.recordHttp(WorkspaceProviderRegistry.Id.OPENROUTER_FREE, 429, "2", nowMs = 3_000L)
        assertFalse(h.canSend(WorkspaceProviderRegistry.Id.OPENROUTER_FREE, nowMs = 4_000L))
        assertEquals("", h.cooldownMessage(
            WorkspaceProviderRegistry.Id.OPENROUTER_FREE, nowMs = 5_000L))
    }

    @Test fun successClearsPriorCooldown() {
        h.recordHttp(WorkspaceProviderRegistry.Id.LLM7_FREE, 429, nowMs = 1_000L)
        assertFalse(h.canSend(WorkspaceProviderRegistry.Id.LLM7_FREE, nowMs = 2_000L))
        h.recordHttp(WorkspaceProviderRegistry.Id.LLM7_FREE, 200, nowMs = 2_000L)
        assertTrue(h.canSend(WorkspaceProviderRegistry.Id.LLM7_FREE, nowMs = 2_000L))
        assertNull(h.snapshot(WorkspaceProviderRegistry.Id.LLM7_FREE, nowMs = 2_000L))
    }
}
