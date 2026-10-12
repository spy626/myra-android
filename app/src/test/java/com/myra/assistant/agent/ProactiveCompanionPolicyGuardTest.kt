package com.myra.assistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveCompanionPolicyGuardTest {
    private val candidate = ProactiveCandidate("update", .95, .93, .94)
    @Test fun neverInterruptsActiveUserTaskOrScreenSessionWithoutConsent() {
        val policy = ProactiveCompanionPolicy()
        val guard = ProactiveSessionGuard(true, true, false, false, true)
        assertFalse(policy.shouldSpeakSafely(candidate, guard.copy(userOptedIn = false), 200_000L))
        assertFalse(policy.shouldSpeakSafely(candidate, guard.copy(taskBusy = true), 200_000L))
        assertFalse(policy.shouldSpeakSafely(candidate, guard.copy(foreground = false), 200_000L))
        assertFalse(policy.shouldSpeakSafely(candidate, guard.copy(awaitingUserReply = true), 200_000L))
        assertTrue(policy.shouldSpeakSafely(candidate, guard, 200_000L))
    }
}
