package com.myra.assistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityObservationCoalescerTest {
    private val element = SemanticElement("1", SemanticRole.BUTTON, "Open", 0, 0, 100, 50, true)

    @Test fun identical_observations_are_coalesced() {
        val gate = ActivityObservationCoalescer()
        assertTrue(gate.shouldPublish(context("app", 1, "PAGE")))
        assertFalse(gate.shouldPublish(context("app", 1, "PAGE").copy(timestamp = 99)))
    }

    @Test fun package_window_modal_and_verification_publish_immediately() {
        val gate = ActivityObservationCoalescer()
        assertTrue(gate.shouldPublish(context("app", 1, "PAGE")))
        assertTrue(gate.shouldPublish(context("other", 1, "PAGE")))
        assertTrue(gate.shouldPublish(context("other", 2, "PAGE")))
        assertTrue(gate.shouldPublish(context("other", 2, "DIALOG")))
        assertTrue(gate.shouldPublish(context("other", 2, "DIALOG"), force = true))
    }

    private fun context(pkg: String, window: Int, type: String) = CurrentActivityContext(
        pkg, windowId = window, generation = 1, screenType = type,
        visibleElements = listOf(element), confidence = .9, timestamp = 1
    )
}
