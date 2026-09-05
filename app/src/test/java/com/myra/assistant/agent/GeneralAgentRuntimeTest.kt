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

    @Test fun planner_chooses_execution_capability_instead_of_first_set_entry() {
        val runtime = GeneralAgentRuntime(now = { 1 })
        val structured = intent(ToolCapability.OBSERVE_SCREEN).copy(
            requiredCapabilities = linkedSetOf(ToolCapability.OBSERVE_SCREEN, ToolCapability.BROWSER_SEARCH),
            textHint = "android ai"
        )
        val task = runtime.start(21, structured)!!
        val result = runtime.next(perception(task.id, scene("com.android.chrome", 1))) as PlannerResult.Next
        assertEquals(ToolCapability.BROWSER_SEARCH, result.step.capability)
    }

    @Test fun planner_requests_observation_when_screen_capability_has_no_scene() {
        val runtime = GeneralAgentRuntime(now = { 1 })
        runtime.start(22, intent(ToolCapability.ACCESSIBILITY_SCROLL))
        assertTrue(runtime.next(null) is PlannerResult.NeedObservation)
    }

    @Test fun waiting_action_requests_verification_instead_of_second_execution() {
        val runtime = GeneralAgentRuntime(now = { 1 })
        val task = runtime.start(23, intent(ToolCapability.ACCESSIBILITY_SCROLL))!!
        val before = perception(task.id, scene("pkg", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        runtime.recordAction(step, GeneralActionResult(true), before)
        assertTrue(runtime.next(before.copy(capturedAt = 2)) is PlannerResult.VerifyPrevious)
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

    @Test fun semantic_count_change_alone_does_not_verify_scroll() {
        val runtime = GeneralAgentRuntime(now = { 4 })
        val task = runtime.start(4, intent(ToolCapability.ACCESSIBILITY_SCROLL))!!
        val before = perception(task.id, scene("pkg", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        runtime.recordAction(step, GeneralActionResult(true), before)
        val after = perception(task.id, scene("pkg", 2, listOf(element("new"))))
        val (result, recovery) = runtime.verify(after)
        assertEquals(GeneralVerificationStatus.UNKNOWN, result.status)
        assertTrue(recovery is RecoveryDecision.Clarify)
    }

    @Test fun stable_anchors_moving_vertically_verify_scroll() {
        val before = scene("pkg", 1, listOf(element("one", top = 300), element("two", top = 500)))
        val after = scene("pkg", 1, listOf(element("one", top = 180), element("two", top = 380)))
        val evidence = ScrollMovementAnalyzer.analyze(before, after)
        assertTrue(evidence.proven)
        assertEquals(2, evidence.movedAnchorCount)
        assertEquals(-120, evidence.medianDeltaY)
    }

    @Test fun node_churn_without_anchor_displacement_is_not_scroll_success() {
        val before = scene("pkg", 1, listOf(element("one", top = 300), element("two", top = 500)))
        val after = scene("pkg", 2, listOf(element("one", top = 300), element("two", top = 500), element("new", top = 700)))
        assertFalse(ScrollMovementAnalyzer.analyze(before, after).proven)
    }

    @Test fun validated_adapter_outcome_completes_same_general_task_owner() {
        val runtime = GeneralAgentRuntime(now = { 7 })
        val task = runtime.start(44, intent(ToolCapability.BROWSER_SEARCH))!!
        val before = perception(task.id, scene("com.android.chrome", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        runtime.recordAction(step, GeneralActionResult(true), before)
        val completed = runtime.completeFromAdapter(GeneralVerificationStatus.SUCCESS, "search results visible")
        assertEquals(task.id, completed?.id)
        assertEquals(44L, completed?.turnId)
        assertEquals(AgentRuntimeStatus.COMPLETED, completed?.status)
        assertNull(runtime.activeTask())
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

    @Test fun failed_action_enters_real_recovery_and_planner_returns_recover_step() {
        val runtime = GeneralAgentRuntime(now = { 5 })
        val task = runtime.start(24, intent(ToolCapability.ACCESSIBILITY_SCROLL))!!
        val before = perception(task.id, scene("pkg", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        runtime.recordAction(step, GeneralActionResult(false, targetId = "container-a", failureReason = "rejected"), before)
        val (_, decision) = runtime.verify(before.copy(capturedAt = 2))
        assertTrue(decision is RecoveryDecision.Retry)
        assertTrue("container-a" in runtime.activeTask()!!.rejectedTargets)
        val recovered = runtime.next(before.copy(capturedAt = 3)) as PlannerResult.Recover
        assertEquals("safe_retry_1", recovered.step.strategy)
    }

    @Test fun production_router_registers_required_real_capability_adapters_and_executes_once() {
        var scrollCalls = 0
        val registry = AgentToolRegistry()
        val adapters = ProductionGeneralAdapters.create(registry, ProductionAdapterExecutors(
            scroll = { _, _ -> scrollCalls++; GeneralActionResult(true) },
            browserSearch = { _, _ -> GeneralActionResult(true) },
            observeScreen = { _, _ -> GeneralActionResult(true) },
            verifyScreen = { _, _ -> GeneralActionResult(true) },
            back = { _, _ -> GeneralActionResult(true) }
        ))
        val router = GeneralActionRouter(adapters)
        assertTrue(router.registeredCapabilities().containsAll(setOf(
            ToolCapability.ACCESSIBILITY_SCROLL, ToolCapability.BROWSER_SEARCH,
            ToolCapability.OBSERVE_SCREEN, ToolCapability.VERIFY_SCREEN, ToolCapability.BACK
        )))
        val runtime = GeneralAgentRuntime(now = { 1 })
        val task = runtime.start(25, intent(ToolCapability.ACCESSIBILITY_SCROLL))!!
        val before = perception(task.id, scene("unknown.app", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        val adapter = router.select(step, before)!!
        runtime.recordAction(step, adapter.execute(step, before), before)
        assertEquals("GenericScrollAdapter", adapter.adapterId)
        assertEquals(1, scrollCalls)
        assertTrue(runtime.next(before.copy(capturedAt = 2)) is PlannerResult.VerifyPrevious)
    }

    @Test fun browser_search_routes_through_adapter_and_completes_from_general_verifier() {
        var searchCalls = 0
        val registry = AgentToolRegistry()
        val adapters = ProductionGeneralAdapters.create(registry, ProductionAdapterExecutors(
            scroll = { _, _ -> GeneralActionResult(true) },
            browserSearch = { _, _ -> searchCalls++; GeneralActionResult(true) },
            observeScreen = { _, _ -> GeneralActionResult(true) },
            verifyScreen = { _, _ -> GeneralActionResult(true) },
            back = { _, _ -> GeneralActionResult(true) }
        ))
        val runtime = GeneralAgentRuntime(now = { 1 })
        val searchIntent = intent(ToolCapability.BROWSER_SEARCH).copy(
            relevantApp = "com.android.chrome", textHint = "new ai", parameters = mapOf("query" to "new ai")
        )
        val task = runtime.start(26, searchIntent)!!
        val before = perception(task.id, scene("com.android.chrome", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        val adapter = GeneralActionRouter(adapters).select(step, before)!!
        runtime.recordAction(step, adapter.execute(step, before), before)
        val after = perception(task.id, scene("com.android.chrome", 2, listOf(element("new ai results"))))
        val (verification, recovery) = runtime.verify(after)
        assertEquals(1, searchCalls)
        assertEquals(GeneralVerificationStatus.SUCCESS, verification.status)
        assertNull(recovery)
        assertEquals(AgentRuntimeStatus.COMPLETED, runtime.lastCompletedTask()?.status)
    }

    @Test fun changed_but_unproven_search_result_remains_unknown_not_failure() {
        val runtime = GeneralAgentRuntime(now = { 1 })
        val task = runtime.start(27, intent(ToolCapability.BROWSER_SEARCH).copy(
            relevantApp = "com.android.chrome", textHint = "new ai"
        ))!!
        val before = perception(task.id, scene("com.android.chrome", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        runtime.recordAction(step, GeneralActionResult(true), before)
        val after = perception(task.id, scene("com.android.chrome", 2, listOf(element("unrelated"))))
        val (verification, _) = runtime.verify(after)
        assertEquals(GeneralVerificationStatus.UNKNOWN, verification.status)
    }

    @Test fun protected_modal_pauses_planner_before_adapter_selection() {
        val runtime = GeneralAgentRuntime(now = { 1 })
        val task = runtime.start(28, intent(ToolCapability.ACCESSIBILITY_SCROLL))!!
        val permission = perception(task.id, scene("pkg", 1).copy(modal = ModalKind.PERMISSION))
        assertTrue(runtime.next(permission) is PlannerResult.NeedClarification)
    }

    @Test fun modal_pauses_old_plan_before_retry() {
        val recovery = GeneralRecoveryEngine()
        val task = GeneralRuntimeTask(turnId = 1, intent = intent(ToolCapability.ACCESSIBILITY_CLICK), createdAt = 1)
        val modal = scene("pkg", 2).copy(modal = ModalKind.PERMISSION)
        val result = GeneralVerificationResult(GeneralVerificationStatus.FAILURE, "page", "permission dialog", .9)
        assertTrue(recovery.decide(task, result, modal, "x") is RecoveryDecision.PauseForModal)
    }

    @Test fun permission_modal_requires_authorization_but_menu_does_not() {
        val protected = scene("pkg", 2).copy(modal = ModalKind.PERMISSION)
        val menu = scene("pkg", 2).copy(modal = ModalKind.MENU)
        assertTrue(ModalSafetyPolicy.requiresAuthorization(protected))
        assertFalse(ModalSafetyPolicy.requiresAuthorization(menu))
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
