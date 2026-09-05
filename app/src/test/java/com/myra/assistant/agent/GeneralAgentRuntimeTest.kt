package com.myra.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralAgentRuntimeTest {
    @Test fun conversation_never_creates_runtime_task_or_tool_step() {
        val runtime = GeneralAgentRuntime()
        val intent = StructuredAgentIntent(TurnIntent.CONVERSATION,
            "Chrome YouTube open click comment search second are useful concepts",
            "architecture discussion", false, confidence = .98)
        assertNull(runtime.start(1, intent))
        assertTrue(runtime.next(null) is PlannerResult.Fail)
    }

    @Test fun search_goal_selects_capability_not_app_specific_brain() {
        val runtime = GeneralAgentRuntime(now = { 1 })
        val task = runtime.start(2, intent(ToolCapability.BROWSER_SEARCH))!!
        val next = runtime.next(perception(task.id, scene("com.android.chrome", 1))) as PlannerResult.Next
        assertEquals(ToolCapability.BROWSER_SEARCH, next.step.capability)
        assertEquals(ActionCategory.BROWSER, next.step.category)
    }

    @Test fun accepted_dispatch_without_observed_change_is_unknown_not_success() {
        val runtime = GeneralAgentRuntime(now = { 2 })
        val task = runtime.start(3, intent(ToolCapability.ACCESSIBILITY_SCROLL))!!
        val before = perception(task.id, scene("pkg", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        runtime.recordAction(step, GeneralActionResult(true), before)
        val (result, _) = runtime.verify(before.copy(capturedAt = 3))
        assertEquals(GeneralVerificationStatus.UNKNOWN, result.status)
    }

    @Test fun fresh_changed_observation_completes_verified_task() {
        val runtime = GeneralAgentRuntime(now = { 4 })
        val task = runtime.start(4, intent(ToolCapability.ACCESSIBILITY_SCROLL))!!
        val before = perception(task.id, scene("pkg", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        runtime.recordAction(step, GeneralActionResult(true), before)
        val after = perception(task.id, scene("pkg", 2, listOf(element("new"))))
        val (result, recovery) = runtime.verify(after)
        assertEquals(GeneralVerificationStatus.SUCCESS, result.status)
        assertNull(recovery)
        assertEquals(AgentRuntimeStatus.COMPLETED, runtime.lastCompletedTask()?.status)
    }

    @Test fun wrong_result_rejects_target_and_recovery_is_bounded() {
        val runtime = GeneralAgentRuntime(recovery = GeneralRecoveryEngine(maxRetries = 0), now = { 5 })
        val requested = intent(ToolCapability.ACCESSIBILITY_CLICK).copy(roleHint = SemanticRole.VIDEO)
        val task = runtime.start(5, requested)!!
        val before = perception(task.id, scene("pkg", 1, listOf(element("target"))))
        val step = (runtime.next(before) as PlannerResult.Next).step
        runtime.recordAction(step, GeneralActionResult(true, "target"), before)
        val wrong = perception(task.id, scene("pkg", 2, listOf(element("channel"))))
        val (result, recovery) = runtime.verify(wrong)
        assertEquals(GeneralVerificationStatus.FAILURE, result.status)
        assertTrue(recovery is RecoveryDecision.Clarify)
    }

    @Test fun modal_pauses_old_plan_before_retry() {
        val recovery = GeneralRecoveryEngine()
        val task = GeneralRuntimeTask(turnId = 1, intent = intent(ToolCapability.ACCESSIBILITY_CLICK), createdAt = 1)
        val modal = scene("pkg", 2).copy(modal = ModalKind.PERMISSION)
        val result = GeneralVerificationResult(GeneralVerificationStatus.FAILURE, "page", "permission dialog", .9)
        assertTrue(recovery.decide(task, result, modal, "x") is RecoveryDecision.PauseForModal)
    }

    @Test fun target_from_old_app_or_generation_is_rejected() {
        val perception = perception("task", scene("app.b", 4, listOf(element("x"))))
        val old = ResolvedActionTarget("x", "app.a", 1, 3, listOf(0, 0, 10, 10))
        assertFalse(GeneralActionSafety.validate(old, perception))
    }

    @Test fun unknown_app_spatial_target_uses_general_scene_geometry() {
        val left = element("left", left = 0, top = 0)
        val right = element("settings", left = 900, top = 0)
        val result = GeneralSemanticTargetResolver().resolve(
            SemanticTargetRequest("upper right settings-like control", spatialHint = SpatialHint.TOP_RIGHT),
            scene("unknown.app", 1, listOf(left, right))
        )
        assertEquals("settings", (result as SemanticTargetResolution.Unique).element.id)
    }

    @Test fun ordinal_is_applied_to_unique_logical_groups() {
        val firstThumb = element("thumb1", top = 10, group = "card1")
        val firstTitle = element("title1", top = 20, group = "card1")
        val second = element("card2", top = 100, group = "card2")
        val result = GeneralSemanticTargetResolver().resolve(
            SemanticTargetRequest("second logical result", ordinal = 2),
            scene("unknown", 1, listOf(firstThumb, firstTitle, second))
        )
        assertEquals("card2", (result as SemanticTargetResolution.Unique).element.id)
    }

    @Test fun equal_candidates_require_clarification_instead_of_random_tap() {
        val result = GeneralSemanticTargetResolver().resolve(
            SemanticTargetRequest("this"), scene("unknown", 1, listOf(element("a"), element("b")))
        )
        assertTrue(result is SemanticTargetResolution.Ambiguous)
    }

    @Test fun app_overlay_keeps_external_package_in_perception() {
        val context = CurrentActivityContext("com.myra.assistant", "LYRA", "OVERLAY", 9, 2, emptyList(), confidence = .9, timestamp = 1)
        assertEquals("external.app", ScreenSceneFactory.from(context, "external.app").externalForegroundPackage)
    }

    private fun intent(capability: ToolCapability) = StructuredAgentIntent(
        TurnIntent.ACTION_REQUEST, "do it", "goal", true, targetDescription = "target",
        requiredCapabilities = setOf(capability), confidence = .9
    )

    private fun scene(pkg: String, generation: Long, elements: List<SemanticElement> = emptyList()) =
        ScreenScene(pkg, pkg, windowId = 1, generation = generation, screenType = "UNKNOWN",
            semanticElements = elements, screenshotReference = null, observedAt = generation, confidence = .9)

    private fun perception(task: String?, scene: ScreenScene) = PerceptionSnapshot(scene, task, scene.observedAt)

    private fun element(id: String, left: Int = 0, top: Int = 0, group: String? = null) =
        SemanticElement(id, SemanticRole.BUTTON, id, left, top, left + 100, top + 100, true, groupId = group)
}
