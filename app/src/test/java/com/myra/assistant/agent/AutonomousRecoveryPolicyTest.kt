package com.myra.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousRecoveryPolicyTest {
    private fun task(
        raw: String = "search Kotlin coroutines karo",
        query: String = "Kotlin coroutines",
        capabilities: Set<ToolCapability> = setOf(
            ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH,
            ToolCapability.OBSERVE_SCREEN, ToolCapability.VERIFY_SCREEN,
        ),
    ) = GeneralRuntimeTask(
        id = "task-1", turnId = 44L,
        intent = StructuredAgentIntent(
            TurnIntent.MULTI_STEP_GOAL, raw, "WEB_SEARCH", true,
            requiredCapabilities = capabilities,
            parameters = mapOf("query" to query), confidence = .95),
        createdAt = 100L
    )

    @Test fun priorAuthorizedPublicSearchAutomaticallyReenters() {
        assertEquals("Kotlin coroutines",
            AutonomousRecoveryPolicy.safeSearch(task())?.query)
    }
    @Test fun neverPersistsPrivateInfoOrPhoneControlForAutoRestart() {
        assertNull(AutonomousRecoveryPolicy.safeSearch(task(
            capabilities = setOf(ToolCapability.ACCESSIBILITY_CLICK, ToolCapability.BROWSER_SEARCH))))
        assertNull(AutonomousRecoveryPolicy.safeSearch(task(
            raw = "search my bank account karo", query = "my bank account")))
        assertNull(AutonomousRecoveryPolicy.safeSearch(task(query = "different")))
        assertNull(AutonomousRecoveryPolicy.safeSearch(
            task().copy(status = AgentRuntimeStatus.WAITING_FOR_USER_AUTH)))
        assertNull(AutonomousRecoveryPolicy.sanitizePublicQuery("OTP 123456"))
        assertNull(AutonomousRecoveryPolicy.sanitizePublicQuery("https://private.example"))
    }
    @Test fun onlyRecentlyInterruptedSearchCanAutorestart() {
        val c = GeneralTaskCheckpointStore.Checkpoint(
            "task-1", 44L, 2, AgentRuntimeStatus.OBSERVING,
            emptyList(), true, false, 1000L, safeSearchQuery = "Kotlin coroutines")
        assertTrue(AutonomousRecoveryPolicy.autoResumeAllowed(c, 2000L))
        assertFalse(AutonomousRecoveryPolicy.autoResumeAllowed(c,
            1000L + 25L * 60 * 60 * 1000))
        assertFalse(AutonomousRecoveryPolicy.autoResumeAllowed(c.copy(
            status = AgentRuntimeStatus.WAITING_FOR_USER_AUTH), 2000L))
    }
}
