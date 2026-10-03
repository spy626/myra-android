package com.myra.assistant.screen

import com.myra.assistant.agent.*
import com.myra.assistant.diagnostics.TurnLatencyTelemetry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class Stability1041Test {
    @After fun cleanup() { ScreenSceneAwarenessStore.reset(); GeneralAgentRuntimeStore.runtime.cancel() }
    private fun element(label: String, x: Int) = SemanticElement(label, SemanticRole.BUTTON, label, x, 100, x + 60, 160, true)
    private fun context(items: List<SemanticElement>) = CurrentActivityContext(
        "com.google.android.googlequicksearchbox", windowId = 1, generation = 1,
        visibleElements = items, confidence = .9, timestamp = 1000)

    @Test fun identicalReorderedAndJitteredScenesDoNotMutate() {
        val items = listOf(element("A", 100), element("B", 800))
        val first = ScreenSceneAwarenessStore.publish(context(items))
        repeat(38) { ScreenSceneAwarenessStore.markMutation("accessibility_window_content") }
        val same = ScreenSceneAwarenessStore.publish(context(items.reversed()))
        assertEquals(first.sceneRevision, same.sceneRevision)
        assertTrue(ScreenSceneAwarenessStore.lastDelta()!!.changes.isEmpty())
        val jitter = ScreenSceneAwarenessStore.publish(context(items.map { it.copy(left = it.left + 2, right = it.right + 2) }))
        assertEquals(first.sceneRevision, jitter.sceneRevision)
        assertTrue(ScreenSceneAwarenessStore.lastDelta()!!.changes.isEmpty())
        val moved = ScreenSceneAwarenessStore.publish(context(listOf(element("A", 800), element("B", 100))))
        assertTrue(moved.sceneRevision > first.sceneRevision)
        assertTrue(ScreenSceneAwarenessStore.lastDelta()!!.changes.any { it.type == SceneDeltaType.ELEMENT_MOVED })
    }

    @Test fun burstCoalescesButExplicitFreshCanBypass() {
        val gate = ScreenRefreshGate()
        assertEquals(150L, gate.requestEvent(0))
        repeat(37) { assertNull(gate.requestEvent(1)) }
        assertFalse(gate.watcherNeeded(100, 1000))
        gate.started() // explicit fresh request cancels pending callback in service
        gate.completed(100)
        assertFalse(gate.watcherNeeded(200, 1000))
        assertTrue(gate.watcherNeeded(1100, 1000))
    }

    @Test fun sustainedBurstHasBoundedRebuilds() {
        val gate = ScreenRefreshGate()
        var due: Long? = null
        var builds = 0
        for (now in 0L..1000L step 10) {
            gate.requestEvent(now)?.let { due = now + it }
            if (due?.let { now >= it } == true) {
                gate.started(); gate.completed(now); builds++; due = null
            }
        }
        assertTrue(builds <= 4)
    }

    @Test fun threeGoogleSearchesUseIndependentRuntimeAndAdapter() {
        val ids = mutableSetOf<String>()
        var dispatches = 0
        val agent = UnifiedLyraAgent()
        val router = GeneralActionRouter(ProductionGeneralAdapters.create(AgentToolRegistry(), ProductionAdapterExecutors(
            scroll = { _, _ -> error("unexpected scroll") },
            browserSearch = { _, _ -> dispatches++; GeneralActionResult(true) },
            observeScreen = { _, _ -> GeneralActionResult(true) },
            verifyScreen = { _, _ -> GeneralActionResult(true) },
            back = { _, _ -> error("unexpected back") }
        )))
        listOf("Search karo new AI", "Search karo Gemini", "Search karo Android news").forEachIndexed { index, raw ->
            agent.acceptTurn(raw, context(emptyList()), true, (index + 1).toLong())
            val runtime = GeneralAgentRuntimeStore.runtime
            val task = runtime.activeTask()!!
            assertTrue(ids.add(task.id))
            val request = FinalSearchHandoff.parse(raw)!!
            val destination = SearchDestinationResolver.resolveDetailed(request, context(emptyList()).packageName, null)
            assertEquals("CURRENT_GOOGLE_APP", destination.selectedExecutor?.name)
            val perception = PerceptionSnapshot(ScreenSceneFactory.from(context(emptyList()), null), task.id, 1000)
            val step = (runtime.next(perception) as PlannerResult.Next).step
            assertEquals(ToolCapability.BROWSER_SEARCH, step.capability)
            val adapter = router.select(step, perception)!!
            assertEquals("BrowserSearchAdapter", adapter.adapterId)
            runtime.recordAction(step, adapter.execute(step, perception), perception)
            runtime.completeFromAdapter(GeneralVerificationStatus.SUCCESS, "results")
        }
        assertEquals(3, dispatches)
    }

    @Test fun punctuationAndPredicateQueriesStayInFinalSearchHandoff() {
        assertEquals("New way", FinalSearchHandoff.parse("New way search karo.")?.query)
        assertEquals("new AI", FinalSearchHandoff.parse("Chat search karo new AI.")?.query)
        assertFalse(SearchProposalPolicy.MAY_EXECUTE)
        assertFalse(SearchProposalPolicy.MAY_REPORT_FAILURE)
        assertEquals("WAIT_FOR_FINAL", SearchProposalPolicy.DECISION)
    }

    @Test fun audioTimestampRetainsOriginalPerGeneration() {
        val t = TurnLatencyTelemetry { }
        t.record(1, TurnLatencyTelemetry.Field.FIRST_ACCEPTED_MODEL_AUDIO, 100, 7)
        t.record(1, TurnLatencyTelemetry.Field.FIRST_ACCEPTED_MODEL_AUDIO, 200, 7)
        t.record(1, TurnLatencyTelemetry.Field.FIRST_ACCEPTED_MODEL_AUDIO, 300, 8)
        assertEquals(100L, t.firstAcceptedAudioAt(1, 7))
        assertEquals(300L, t.firstAcceptedAudioAt(1, 8))
    }

    @Test fun eyesAndProjectionHaveIndependentSettingsWiring() {
        val root = java.io.File("src/main").takeIf { it.exists() } ?: java.io.File("app/src/main")
        val activity = java.io.File(root, "java/com/myra/assistant/ui/settings/ScreenVisionSettingsActivity.kt").readText()
        assertTrue(activity.contains("binding.eyesSwitch.isChecked = eyes.enabled"))
        assertTrue(activity.contains("eyes.enabled = checked"))
        assertTrue(activity.contains("binding.visionSwitch.isChecked = preferences.visionEnabled"))
        assertTrue(activity.contains("preferences.visionEnabled = checked"))
        val service = java.io.File(root, "java/com/myra/assistant/service/AccessibilityHelperService.kt").readText()
        assertFalse(service.contains("TYPE_ACCESSIBILITY_OVERLAY"))
    }
}
