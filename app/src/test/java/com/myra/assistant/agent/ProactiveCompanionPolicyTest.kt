package com.myra.assistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveCompanionPolicyTest {
    @Test fun intervention_requires_toggle_threshold_cooldown_and_novelty() {
        val policy = ProactiveCompanionPolicy(cooldownMs = 1000, threshold = .7)
        val useful = ProactiveCandidate("build-failed", .9, .9, .9)
        assertFalse(policy.shouldSpeak(useful, false, 2000))
        assertTrue(policy.shouldSpeak(useful, true, 2000))
        assertFalse(policy.shouldSpeak(useful, true, 4000))
        assertFalse(policy.shouldSpeak(ProactiveCandidate("weak", .2, .2, .2), true, 4000))
    }
}
