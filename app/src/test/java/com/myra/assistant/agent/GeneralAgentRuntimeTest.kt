package com.myra.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralAgentRuntimeTest {
    @Test fun inFlightStepCannotBeRedispatchedOrReplaced() {
        val runtime = GeneralAgentRuntime(now = { 10 })
        val task = runtime.start(400L, intent(ToolCapability.BROWSER_SEARCH))!!
        val before = perception(task.id, scene("com.android.chrome", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        assertTrue(runtime.claimStepExecution(step, 400L))
        assertFalse(runtime.claimStepExecution(step, 400L))
        assertTrue(runtime.next(before) is PlannerResult.InFlight)
        runtime.recordAction(step, GeneralActionResult(true), before)
        assertTrue(runtime.next(before.copy(capturedAt = 2)) is PlannerResult.VerifyPrevious)
        assertFalse(runtime.claimStepExecution(step, 400L))
    }

    @Test fun staleStepCannotRecordIntoSameTaskOrDifferentTurn() {
        val runtime = GeneralAgentRuntime(now = { 10 })
        val task = runtime.start(401L, intent(ToolCapability.ACCESSIBILITY_SCROLL))!!
        val before = perception(task.id, scene("pkg", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        assertFalse(runtime.claimStepExecution(step, 400L))
        assertFalse(runtime.claimStepExecution(step.copy(id = "wrong"), 401L))
        var rejected = false
        try {
            runtime.recordAction(step.copy(id = "wrong"), GeneralActionResult(true), before)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertTrue(runtime.claimStepExecution(step, 401L))
    }

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

    @Test fun plannerAutomaticallyChoosesDeclaredAvailableEquivalentSearchTool() {
        // A model/turn can propose both read-only search capabilities, but only the owner
        // knows which adapter is actually available. No new tool or permission is invented.
        val available = AgentToolRegistry(listOf(
            ToolDefinition("web_only", ToolCapability.WEB_SEARCH,
                verificationStrategy = "grounded_results_returned")
        ))
        val runtime = GeneralAgentRuntime(registry = available, now = { 1 })
        val intent = intent(ToolCapability.BROWSER_SEARCH).copy(
            requiredCapabilities = linkedSetOf(
                ToolCapability.OBSERVE_SCREEN, ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH
            ), textHint = "public research"
        )
        runtime.start(30, intent)
        val next = runtime.next(null) as PlannerResult.Next
        assertEquals(ToolCapability.WEB_SEARCH, next.step.capability)
        assertEquals(ActionCategory.WEB_SEARCH, next.step.category)
        assertEquals(ToolRisk.LOW, next.step.risk)
    }

    @Test fun plannerDoesNotInventAnUnrequestedToolOrSubstituteObservationForClick() {
        val webOnly = GeneralAgentRuntime(registry = AgentToolRegistry(listOf(
            ToolDefinition("web_only", ToolCapability.WEB_SEARCH,
                verificationStrategy = "grounded_results_returned")
        )), now = { 1 })
        webOnly.start(31, intent(ToolCapability.BROWSER_SEARCH))
        assertEquals(PlannerResult.Fail("no_safe_tool"), webOnly.next(null))

        val observeOnly = GeneralAgentRuntime(registry = AgentToolRegistry(listOf(
            ToolDefinition("observe_only", ToolCapability.OBSERVE_SCREEN,
                verificationStrategy = "fresh_context")
        )), now = { 1 })
        observeOnly.start(32, intent(ToolCapability.ACCESSIBILITY_CLICK).copy(
            requiredCapabilities = setOf(
                ToolCapability.ACCESSIBILITY_CLICK, ToolCapability.OBSERVE_SCREEN
            )
        ))
        assertEquals(PlannerResult.Fail("no_safe_tool"),
            observeOnly.next(perception("task", scene("pkg", 1))))
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

    @Test fun unknownScrollEvidenceUsesVerificationOnlyResamplingWithoutAnotherAction() {
        var gestureCount = 0
        gestureCount++
        assertTrue(ScrollVerificationResamplePolicy.shouldResample(true, false, 0))
        assertTrue(ScrollVerificationResamplePolicy.shouldResample(true, false, 1))
        assertFalse(ScrollVerificationResamplePolicy.shouldResample(true, false, 2))
        assertEquals(1, gestureCount)
        assertEquals(250L, ScrollVerificationResamplePolicy.DELAY_MS)
    }

    @Test fun provenScrollDoesNotScheduleVerificationResample() {
        assertFalse(ScrollVerificationResamplePolicy.shouldResample(true, true, 0))
        assertFalse(ScrollVerificationResamplePolicy.shouldResample(false, false, 0))
    }

    @Test fun autonomousMultiGoalPerformsOneReadOnlyFollowupWithoutRedispatchingSearch() {
        var searchCount = 0
        var observationCount = 0
        val adapters = ProductionGeneralAdapters.create(AgentToolRegistry(), ProductionAdapterExecutors(
            scroll = { _, _ -> GeneralActionResult(false) },
            browserSearch = { _, _ -> searchCount++; GeneralActionResult(true) },
            observeScreen = { _, _ -> observationCount++; GeneralActionResult(true) },
            verifyScreen = { _, _ -> GeneralActionResult(false) },
            back = { _, _ -> GeneralActionResult(false) }
        ))
        val runtime = GeneralAgentRuntime(now = { 8 })
        val goal = intent(ToolCapability.BROWSER_SEARCH).copy(
            turnIntent = TurnIntent.MULTI_STEP_GOAL,
            requiredCapabilities = setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.OBSERVE_SCREEN),
            relevantApp = "com.android.chrome", textHint = "new ai")
        val task = runtime.start(60, goal)!!
        val before = perception(task.id, scene("com.android.chrome", 1))
        val first = (runtime.next(before) as PlannerResult.Next).step
        runtime.recordAction(first, GeneralActionRouter(adapters).select(first, before)!!.execute(first, before), before)
        val searched = perception(task.id, scene("com.android.chrome", 2,
            listOf(element("new ai results"))))
        assertEquals(GeneralVerificationStatus.SUCCESS, runtime.verify(searched).first.status)
        assertNull(runtime.lastCompletedTask())
        assertEquals(AgentRuntimeStatus.OBSERVING, runtime.activeTask()?.status)
        val second = (runtime.next(searched) as PlannerResult.Next).step
        assertEquals(ToolCapability.OBSERVE_SCREEN, second.capability)
        assertEquals("verified_search_followup_observation", second.strategy)
        runtime.recordAction(second,
            GeneralActionRouter(adapters).select(second, searched)!!.execute(second, searched),
            searched)
        val fresh = perception(task.id, scene("com.android.chrome", 3,
            listOf(element("new ai independent results"))))
        assertEquals(GeneralVerificationStatus.SUCCESS, runtime.verify(fresh).first.status)
        assertEquals(1, searchCount)
        assertEquals(1, observationCount)
        assertEquals(2, runtime.activeTask()!!.verifiedGoalSubsteps.size)
        assertEquals(AgentRuntimeStatus.PLANNING, runtime.activeTask()!!.status)
        assertNull(runtime.lastCompletedTask())
        assertTrue(runtime.next(fresh) is PlannerResult.NeedClarification)
        assertEquals(1, searchCount) // no unverified third action
    }

    @Test fun failedOrUnknownGoalSubstepNeverBecomesLearningEvidence() {
        val runtime = GeneralAgentRuntime(now = { 8 })
        val task = runtime.start(61, intent(ToolCapability.BROWSER_SEARCH).copy(
            turnIntent = TurnIntent.MULTI_STEP_GOAL,
            requiredCapabilities = setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.OBSERVE_SCREEN),
            relevantApp = "com.android.chrome", textHint = "new ai"))!!
        val before = perception(task.id, scene("com.android.chrome", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        runtime.recordAction(step, GeneralActionResult(true), before)
        val irrelevant = perception(task.id, scene("com.android.chrome", 2,
            listOf(element("unrelated result"))))
        assertEquals(GeneralVerificationStatus.UNKNOWN, runtime.verify(irrelevant).first.status)
        assertTrue(runtime.activeTask()?.verifiedGoalSubsteps.orEmpty().isEmpty())
        assertTrue(runtime.next(irrelevant) !is PlannerResult.Next ||
            runtime.activeTask()?.verifiedGoalSubsteps.orEmpty().isEmpty())
    }

    @Test fun plainSingleStepSearchRetainsExistingTerminalSuccessSemantics() {
        val runtime = GeneralAgentRuntime(now = { 1 })
        val task = runtime.start(62, intent(ToolCapability.BROWSER_SEARCH).copy(
            relevantApp = "com.android.chrome", textHint = "new ai"))!!
        val before = perception(task.id, scene("com.android.chrome", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        runtime.recordAction(step, GeneralActionResult(true), before)
        assertEquals(GeneralVerificationStatus.SUCCESS, runtime.verify(perception(
            task.id, scene("com.android.chrome", 2, listOf(element("new ai results"))))).first.status)
        assertNull(runtime.activeTask())
        assertEquals(AgentRuntimeStatus.COMPLETED, runtime.lastCompletedTask()?.status)
    }

    @Test fun verifiedSearchOutcomesChangeNextGoalPlanOnlyWhenBothExecutorsExist() {
        val runtime = GeneralAgentRuntime(now = { 8 })
        fun verifiedWeb(turn: Long) {
            val task = runtime.start(turn, intent(ToolCapability.WEB_SEARCH).copy(
                relevantApp = "com.android.chrome", textHint = "ai"))!!
            val before = perception(task.id, scene("com.android.chrome", turn * 10))
            val step = (runtime.next(before) as PlannerResult.Next).step
            assertEquals(ToolCapability.WEB_SEARCH, step.capability)
            runtime.recordAction(step, GeneralActionResult(true), before)
            val after = perception(task.id, scene("com.android.chrome", turn * 10 + 1,
                listOf(element("ai search results"))))
            assertEquals(GeneralVerificationStatus.SUCCESS, runtime.verify(after).first.status)
        }
        verifiedWeb(80L)
        val insufficient = runtime.start(81L, intent(ToolCapability.BROWSER_SEARCH).copy(
            relevantApp = "com.android.chrome", textHint = "ai",
            requiredCapabilities = setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH)))!!
        assertEquals(ToolCapability.BROWSER_SEARCH, (runtime.next(perception(
            insufficient.id, scene("com.android.chrome", 810L))) as PlannerResult.Next).step.capability)
        verifiedWeb(82L)
        val learned = runtime.start(83L, intent(ToolCapability.BROWSER_SEARCH).copy(
            relevantApp = "com.android.chrome", textHint = "ai",
            requiredCapabilities = setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH)))!!
        val choice = runtime.next(perception(learned.id,
            scene("com.android.chrome", 830L))) as PlannerResult.Next
        assertEquals(ToolCapability.WEB_SEARCH, choice.step.capability)
        assertEquals("verified_outcome_preferred_web_search", choice.step.strategy)

        val constrained = runtime.start(84L, intent(ToolCapability.BROWSER_SEARCH).copy(
            relevantApp = "com.android.chrome", textHint = "ai",
            requiredCapabilities = setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH)))!!
        val safe = runtime.next(perception(constrained.id,
            scene("com.android.chrome", 840L)),
            setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.OBSERVE_SCREEN)) as PlannerResult.Next
        assertEquals(ToolCapability.BROWSER_SEARCH, safe.step.capability)
        assertEquals("primary", safe.step.strategy)
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

    @Test fun acceptedClickWithUnverifiedOutcomeDoesNotGetAutoReplayed() {
        val runtime = GeneralAgentRuntime(now = { 5L })
        val task = runtime.start(29L, intent(ToolCapability.ACCESSIBILITY_CLICK)
            .copy(roleHint = SemanticRole.VIDEO))!!
        val before = perception(task.id, scene("pkg", 1, listOf(element("video"))))
        val step = (runtime.next(before) as PlannerResult.Next).step
        runtime.recordAction(step, GeneralActionResult(true, "video"), before)
        val wrong = perception(task.id, scene("pkg", 2, listOf(element("unrelated"))))
        val (_, recovery) = runtime.verify(wrong)
        assertTrue(recovery is RecoveryDecision.Clarify)
        assertEquals(0, runtime.activeTask()?.recoveryCount)
    }

    @Test fun rejectedHighRiskTypingIsNotAutomaticallyRetried() {
        val runtime = GeneralAgentRuntime(now = { 5L })
        val task = runtime.start(30L, intent(ToolCapability.ACCESSIBILITY_TYPE))!!
        val before = perception(task.id, scene("pkg", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        runtime.recordAction(step, GeneralActionResult(false, failureReason = "rejected"), before)
        val (_, decision) = runtime.verify(before.copy(capturedAt = 3L))
        assertTrue(decision is RecoveryDecision.Clarify)
    }

    @Test fun uncertainAdapterExceptionMustNotSilentlyReplayEvenSafeSearch() {
        val runtime = GeneralAgentRuntime(now = { 5L })
        val task = runtime.start(31L, intent(ToolCapability.BROWSER_SEARCH))!!
        val before = perception(task.id, scene("com.android.chrome", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        assertTrue(runtime.claimStepExecution(step, 31L))
        runtime.recordAction(step, GeneralActionResult(false,
            failureReason = "adapter_exception"), before)
        val (_, recovery) = runtime.verify(before.copy(capturedAt = 2))
        assertTrue(recovery is RecoveryDecision.Clarify)
    }


    @Test fun rejectedBrowserSearchFallsBackOnlyToDeclaredRegisteredNativeSearch() {
        val runtime = GeneralAgentRuntime(now = { 20 })
        val allowed = setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH)
        val task = runtime.start(70, intent(ToolCapability.BROWSER_SEARCH).copy(
            requiredCapabilities = allowed))!!
        val before = perception(task.id, scene("com.android.chrome", 1))
        val first = (runtime.next(before, allowed) as PlannerResult.Next).step
        assertEquals(ToolCapability.BROWSER_SEARCH, first.capability)
        assertTrue(runtime.claimStepExecution(first, 70))
        runtime.recordAction(first, GeneralActionResult(false,
            failureReason = "browser_dispatch_rejected"), before)
        assertTrue(runtime.verify(before.copy(capturedAt = 2)).second is RecoveryDecision.Retry)
        val fallback = (runtime.next(before.copy(capturedAt = 3), allowed) as PlannerResult.Recover).step
        assertEquals(ToolCapability.WEB_SEARCH, fallback.capability)
        assertEquals("rejected_search_fallback_web_search", fallback.strategy)
        assertFalse(runtime.claimStepExecution(first, 70))
        assertTrue(runtime.claimStepExecution(fallback, 70))
    }

    @Test fun rejectedNativeSearchFallsBackToExistingBrowserOnly() {
        val runtime = GeneralAgentRuntime(now = { 20 })
        val allowed = setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH)
        val task = runtime.start(71, intent(ToolCapability.BROWSER_SEARCH).copy(
            requiredCapabilities = allowed,
            parameters = mapOf("preferredSearchCapability" to ToolCapability.WEB_SEARCH.name)))!!
        val before = perception(task.id, scene("com.google.android.googlequicksearchbox", 1))
        val first = (runtime.next(before, allowed) as PlannerResult.Next).step
        assertEquals(ToolCapability.WEB_SEARCH, first.capability)
        assertTrue(runtime.claimStepExecution(first, 71))
        runtime.recordAction(first, GeneralActionResult(false,
            failureReason = "native_search_rejected"), before)
        assertTrue(runtime.verify(before.copy(capturedAt = 2)).second is RecoveryDecision.Retry)
        val alternate = (runtime.next(before.copy(capturedAt = 3), allowed) as PlannerResult.Recover).step
        assertEquals(ToolCapability.BROWSER_SEARCH, alternate.capability)
    }

    @Test fun rejectedSearchCannotInventUnavailableOrUnapprovedFallback() {
        val runtime = GeneralAgentRuntime(now = { 20 })
        val task = runtime.start(72, intent(ToolCapability.BROWSER_SEARCH).copy(
            requiredCapabilities = setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH)))!!
        val before = perception(task.id, scene("com.android.chrome", 1))
        val onlyBrowser = setOf(ToolCapability.BROWSER_SEARCH)
        val step = (runtime.next(before, onlyBrowser) as PlannerResult.Next).step
        assertTrue(runtime.claimStepExecution(step, 72))
        runtime.recordAction(step, GeneralActionResult(false, failureReason = "rejected"), before)
        assertTrue(runtime.verify(before.copy(capturedAt = 2)).second is RecoveryDecision.Retry)
        assertEquals(ToolCapability.BROWSER_SEARCH,
            (runtime.next(before.copy(capturedAt = 3), onlyBrowser) as PlannerResult.Recover).step.capability)
    }

    @Test fun lateActionAndObservationCannotMutateNewerTurn() {
        val runtime = GeneralAgentRuntime(now = { 30 })
        val firstTask = runtime.start(80, intent(ToolCapability.BROWSER_SEARCH))!!
        val before = perception(firstTask.id, scene("com.android.chrome", 1))
        val first = (runtime.next(before) as PlannerResult.Next).step
        assertTrue(runtime.claimStepExecution(first, 80))
        val newer = runtime.start(81, intent(ToolCapability.BROWSER_SEARCH))!!
        assertNull(runtime.recordActionIfCurrent(first, 80, GeneralActionResult(true), before))
        assertNull(runtime.verifyIfCurrent(firstTask.id, 80, first.id,
            perception(firstTask.id, scene("com.android.chrome", 2))))
        assertEquals(newer.id, runtime.activeTask()?.id)
        assertTrue(runtime.activeTask()?.actionHistory.orEmpty().isEmpty())
    }

    @Test fun afterClarificationRuntimeWillNotDispatchAnotherStep() {
        val runtime = GeneralAgentRuntime(now = { 40 })
        val task = runtime.start(82, intent(ToolCapability.BROWSER_SEARCH))!!
        val before = perception(task.id, scene("com.android.chrome", 1))
        val step = (runtime.next(before) as PlannerResult.Next).step
        assertTrue(runtime.claimStepExecution(step, 82))
        runtime.recordAction(step, GeneralActionResult(true), before)
        val after = perception(task.id, scene("com.android.chrome", 2))
        assertEquals(GeneralVerificationStatus.UNKNOWN,
            runtime.verify(after).first.status)
        assertTrue(runtime.next(after) is PlannerResult.NeedClarification)
        assertFalse(runtime.claimStepExecution(step, 82))
    }


    @Test fun autonomousSourceGoalCannotStartWithoutFinalBoundSearchAndObservation() {
        val runtime = GeneralAgentRuntime(now = { 23 })
        val single = runtime.start(300, intent(ToolCapability.BROWSER_SEARCH))!!
        assertNull(runtime.claimSourceAnalysis(single.id, 300))
        assertNull(runtime.claimSourceAnalysis(single.id, 301))
    }

    @Test fun researchRequiresTwoIndependentReadPublicDomains() {
        val evidence = listOf(
            VerifiedResearchSource("https://docs.example.org/a", 2),
            VerifiedResearchSource("https://www.example.org/b", 1),
            VerifiedResearchSource("https://other.example.net/article", 1),
            VerifiedResearchSource("http://insecure.example.net/x", 4),
            VerifiedResearchSource("https://missing.example.edu/a", 0),
        )
        assertEquals(2, AutonomousSourceAnalysisPolicy.independentEvidence(evidence))
        assertEquals(1, AutonomousSourceAnalysisPolicy.independentEvidence(evidence.take(2)))
        assertEquals(0, AutonomousSourceAnalysisPolicy.independentEvidence(emptyList()))
    }

    @Test fun multiLabelPublicSuffixSubdomainsAreOneIndependentPublisher() {
        val evidence = listOf(
            VerifiedResearchSource("https://docs.example.co.uk/a", 2),
            VerifiedResearchSource("https://www.example.co.uk/b", 1),
            VerifiedResearchSource("https://other.co.uk/article", 1),
        )
        assertEquals(1, AutonomousSourceAnalysisPolicy.independentEvidence(evidence.take(2)))
        assertEquals(2, AutonomousSourceAnalysisPolicy.independentEvidence(evidence))
    }

    @Test fun unsafeUrlsOrUnreadPagesCannotProveIndependentResearch() {
        val evidence = listOf(
            VerifiedResearchSource("http://untrusted.example.com/a", 2),
            VerifiedResearchSource("https://127.0.0.1/local", 1),
            VerifiedResearchSource("https://localhost/private", 1),
            VerifiedResearchSource("https://user:pass@example.org/a", 1),
            VerifiedResearchSource("https://real.example.net/a", 0),
        )
        assertEquals(0, AutonomousSourceAnalysisPolicy.independentEvidence(evidence))
    }

    @Test fun researchSourceCallbacksAreSingleUseAndCannotFinishNewTurn() {
        val runtime = GeneralAgentRuntime(now = { 40 })
        val intent = intent(ToolCapability.BROWSER_SEARCH).copy(
            turnIntent = TurnIntent.MULTI_STEP_GOAL,
            originalUtterance = "search android kotlin karo",
            requiredCapabilities = setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.OBSERVE_SCREEN),
            relevantApp = "com.android.chrome", textHint = "android kotlin",
            parameters = mapOf("query" to "android kotlin"))
        val task = runtime.start(301, intent)!!
        val before = perception(task.id, scene("com.android.chrome", 1))
        val search = (runtime.next(before) as PlannerResult.Next).step
        assertTrue(runtime.claimStepExecution(search, 301))
        runtime.recordAction(search, GeneralActionResult(true), before)
        val searched = perception(task.id, scene("com.android.chrome", 2,
            listOf(element("android kotlin results"))))
        assertEquals(GeneralVerificationStatus.SUCCESS, runtime.verify(searched).first.status)
        val observation = (runtime.next(searched) as PlannerResult.Next).step
        assertTrue(runtime.claimStepExecution(observation, 301))
        runtime.recordAction(observation, GeneralActionResult(true), searched)
        val observed = perception(task.id, scene("com.android.chrome", 3,
            listOf(element("android kotlin independent results"))))
        assertEquals(GeneralVerificationStatus.SUCCESS, runtime.verify(observed).first.status)
        assertEquals("android kotlin", runtime.claimSourceAnalysis(task.id, 301))
        assertNull(runtime.claimSourceAnalysis(task.id, 301))
        val matchingEvidence = "Android Kotlin coroutines provide structured asynchronous concurrency in modern mobile application development and support scheduled background work across independent coroutine scopes."
        val result = runtime.completeSourceAnalysisIfCurrent(
            task.id, 301, listOf(
                VerifiedResearchSource("https://example.org/page", 2, listOf(matchingEvidence)),
                VerifiedResearchSource("https://other.example.net/page", 1,
                    listOf(matchingEvidence))),
            "Verified matching passages and citations available")
        assertEquals(AgentRuntimeStatus.COMPLETED, result?.status)
        assertEquals(2, result?.verifiedResearchSourceCount)
        assertNull(runtime.completeSourceAnalysisIfCurrent(task.id, 301,
            emptyList(), "stale"))
        assertEquals(task.id, runtime.lastCompletedTask()?.id)
    }

    @Test fun genericAdapterSuccessCannotFinishResearchWithoutSourceAudit() {
        val runtime = GeneralAgentRuntime(now = { 40 })
        val approved = intent(ToolCapability.BROWSER_SEARCH).copy(
            turnIntent = TurnIntent.MULTI_STEP_GOAL,
            originalUtterance = "search android kotlin karo",
            requiredCapabilities = setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.OBSERVE_SCREEN),
            relevantApp = "com.android.chrome", textHint = "android kotlin",
            parameters = mapOf("query" to "android kotlin"))
        val task = runtime.start(501, approved)!!
        val before = perception(task.id, scene("com.android.chrome", 1))
        val search = (runtime.next(before) as PlannerResult.Next).step
        assertTrue(runtime.claimStepExecution(search, 501))
        runtime.recordAction(search, GeneralActionResult(true), before)
        val searched = perception(task.id, scene("com.android.chrome", 2,
            listOf(element("android kotlin results"))))
        assertEquals(GeneralVerificationStatus.SUCCESS, runtime.verify(searched).first.status)
        val observation = (runtime.next(searched) as PlannerResult.Next).step
        assertTrue(runtime.claimStepExecution(observation, 501))
        runtime.recordAction(observation, GeneralActionResult(true), searched)
        val observed = perception(task.id, scene("com.android.chrome", 3,
            listOf(element("android kotlin independent results"))))
        assertEquals(GeneralVerificationStatus.SUCCESS, runtime.verify(observed).first.status)
        assertEquals("android kotlin", runtime.claimSourceAnalysis(task.id, 501))
        val result = runtime.completeFromAdapter(
            GeneralVerificationStatus.SUCCESS, "Generic adapter claimed success")
        assertEquals(AgentRuntimeStatus.NEEDS_CLARIFICATION, result?.status)
        assertNull(runtime.activeTask())
        assertEquals(0, result?.verifiedResearchSourceCount)
    }

    @Test fun interruptedResearchEvidenceCannotCompleteNewUserTurn() {
        val runtime = GeneralAgentRuntime(now = { 50 })
        val task = runtime.start(401, intent(ToolCapability.BROWSER_SEARCH))!!
        runtime.start(402, intent(ToolCapability.BROWSER_SEARCH))
        assertNull(runtime.completeSourceAnalysisIfCurrent(task.id, 401, listOf(
            VerifiedResearchSource("https://example.com", 1)),
            "Cannot insert a result into the new turn"))
        assertEquals(402L, runtime.activeTask()?.turnId)
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

    @Test fun productionRegistersSecondNativeReadOnlyRouteOnlyWithRealExecutor() {
        val registry = AgentToolRegistry()
        var browserCalls = 0
        var webCalls = 0
        fun adapters(native: Boolean) = ProductionGeneralAdapters.create(registry,
            ProductionAdapterExecutors(
                scroll = { _, _ -> GeneralActionResult(false) },
                browserSearch = { _, _ -> browserCalls++; GeneralActionResult(true) },
                observeScreen = { _, _ -> GeneralActionResult(true) },
                verifyScreen = { _, _ -> GeneralActionResult(true) },
                back = { _, _ -> GeneralActionResult(false) },
                webSearch = if (native) { { _, _ -> webCalls++; GeneralActionResult(true) } } else null
            ))
        assertFalse(ToolCapability.WEB_SEARCH in GeneralActionRouter(adapters(false))
            .registeredCapabilities())
        val router = GeneralActionRouter(adapters(true))
        assertTrue(ToolCapability.WEB_SEARCH in router.registeredCapabilities())

        val google = NativeReadOnlyWebSearchPolicy.GOOGLE_PACKAGE
        val request = intent(ToolCapability.BROWSER_SEARCH).copy(
            relevantApp = google, textHint = "android ai",
            parameters = mapOf("query" to "android ai", "nativeWebSearchEligible" to "true",
                "preferredSearchCapability" to ToolCapability.WEB_SEARCH.name),
            requiredCapabilities = setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH)
        )
        val runtime = GeneralAgentRuntime(now = { 9 })
        val task = runtime.start(501L, request)!!
        val before = perception(task.id, scene(google, 1))
        val step = (runtime.next(before, router.registeredCapabilities())
            as PlannerResult.Next).step
        assertEquals(ToolCapability.WEB_SEARCH, step.capability)
        assertEquals("NativeWebSearchAdapter", router.select(step, before)?.adapterId)
        runtime.recordAction(step, router.select(step, before)!!.execute(step, before), before)
        assertEquals(1, webCalls)
        assertEquals(0, browserCalls)
        val after = perception(task.id, scene(google, 2,
            listOf(element("android ai search results"))))
        assertEquals(GeneralVerificationStatus.SUCCESS, runtime.verify(after).first.status)
        assertEquals(ToolCapability.WEB_SEARCH,
            runtime.takeVerifiedSearchOutcomes().single().capability)
        assertTrue(runtime.takeVerifiedSearchOutcomes().isEmpty())

        val fallback = GeneralAgentRuntime(now = { 10 })
        val next = fallback.start(502L, request)!!
        val default = fallback.next(perception(next.id, scene(google, 1)),
            GeneralActionRouter(adapters(false)).registeredCapabilities()) as PlannerResult.Next
        assertEquals(ToolCapability.BROWSER_SEARCH, default.step.capability)
        assertEquals(1, webCalls)
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
