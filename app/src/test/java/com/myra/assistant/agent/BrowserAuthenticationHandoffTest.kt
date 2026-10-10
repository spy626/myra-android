package com.myra.assistant.agent

import org.junit.Assert.*
import org.junit.Test

class BrowserAuthenticationHandoffTest {
    private val browser = "com.android.chrome"
    private fun element(
        id: String, role: SemanticRole, label: String, actionable: Boolean = false,
    ) = SemanticElement(id, role, label, 0, 0, 140, 40, actionable)
    private val login = listOf(
        element("heading", SemanticRole.TEXT, "Sign in"),
        element("credential", SemanticRole.TEXT_INPUT, "", true),
        element("submit", SemanticRole.BUTTON, "Continue with Google", true)
    )
    private val publicLines = listOf(
        element("one", SemanticRole.TEXT,
            "This public research overview includes the latest product update."),
        element("two", SemanticRole.TEXT,
            "The public update timeline explains the visible release milestones.")
    )
    private fun context(
        at: Long, lines: List<SemanticElement>, pkg: String = browser, window: Int = 4,
        generation: Long = 2,
    ) = CurrentActivityContext(pkg, screenType = "WEB_PAGE",
        windowId = window, generation = generation, visibleElements = lines,
        confidence = .9, timestamp = at)
    private fun intent() = StructuredAgentIntent(
        turnIntent = TurnIntent.MULTI_STEP_GOAL, originalUtterance = "Research public updates",
        interpretedGoal = "read public updates", requiresAction = true,
        relevantApp = browser, textHint = "public update",
        parameters = mapOf("query" to "public update"),
        requiredCapabilities = setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.OBSERVE_SCREEN),
        confidence = .95
    )
    private fun actionStep(id: String) = GeneralPlanStep(
        taskId = id, category = ActionCategory.BROWSER,
        capability = ToolCapability.BROWSER_SEARCH,
        expectedOutcome = ExpectedOutcome(ExpectedOutcomeType.RESULT_SET_CHANGED,
            "matching public results", browser, "public update")
    )

    @Test fun challengeRequiresFreshBrowserTwoIndependentUiSignalsAndTaskAuthority() {
        val task = GeneralRuntimeTask(id = "research-1", turnId = 77L, intent = intent(),
            status = AgentRuntimeStatus.WAITING_FOR_RESULT,
            currentStep = actionStep("research-1"), createdAt = 100L)
        val pause = requireNotNull(BrowserAuthenticationHandoff.detect(
            task, context(1200L, login), 1250L))
        assertEquals("research-1", pause.taskId)
        assertEquals(77L, pause.turnId)
        assertEquals(301250L, pause.deadlineAt)
        assertFalse(pause.toString().contains("Sign in"))
        assertFalse(pause.toString().contains("Continue with Google"))
        assertNull(BrowserAuthenticationHandoff.detect(task,
            context(1200L, login), 3000L))
        assertNull(BrowserAuthenticationHandoff.detect(task,
            context(1200L, login, pkg = "org.mozilla.firefox"), 1250L))
        assertNull(BrowserAuthenticationHandoff.detect(task,
            context(1200L, login.take(1)), 1250L))
        assertNull(BrowserAuthenticationHandoff.detect(
            task.copy(intent = intent().copy(turnIntent = TurnIntent.CONVERSATION)),
            context(1200L, login), 1250L))
        assertNull(BrowserAuthenticationHandoff.detect(
            task.copy(status = AgentRuntimeStatus.COMPLETED),
            context(1200L, login), 1250L))
    }

    @Test fun privateAuthSurfaceNeverCountsAsSuccessfulPostLoginVerification() {
        val task = GeneralRuntimeTask(id = "research-1", turnId = 77L, intent = intent(),
            status = AgentRuntimeStatus.WAITING_FOR_RESULT,
            currentStep = actionStep("research-1"), createdAt = 100L)
        val pause = requireNotNull(BrowserAuthenticationHandoff.detect(
            task, context(1200L, login), 1250L))
        val safe1 = context(2000L, publicLines)
        val safe2 = context(2400L, publicLines)
        assertTrue(BrowserAuthenticationHandoff.canReverify(pause, safe1, safe2, 2450L))
        assertFalse(BrowserAuthenticationHandoff.canReverify(pause, safe1,
            safe2.copy(windowId = 9), 2450L))
        assertFalse(BrowserAuthenticationHandoff.canReverify(pause, safe1,
            safe2.copy(packageName = "org.mozilla.firefox"), 2450L))
        assertFalse(BrowserAuthenticationHandoff.canReverify(pause, safe1,
            safe2.copy(generation = 5), 2450L))
        assertFalse(BrowserAuthenticationHandoff.canReverify(pause, safe1,
            safe2.copy(timestamp = 2150L), 2450L))
        assertFalse(BrowserAuthenticationHandoff.canReverify(pause, safe1,
            context(2400L, publicLines + element("pw", SemanticRole.TEXT_INPUT,
                "Password: secret value", true)), 2450L))
        assertFalse(BrowserAuthenticationHandoff.canReverify(pause, safe1,
            context(2400L, listOf(publicLines.first())), 2450L))
        assertFalse(BrowserAuthenticationHandoff.canReverify(pause, safe1, safe2,
            pause.deadlineAt + 1))
    }

    @Test fun originalResearchStepPausesAndReverifiesAfterUserAuthWithoutRepeatDispatch() {
        val runtime = GeneralAgentRuntime(now = { 1000L })
        val task = requireNotNull(runtime.start(77L, intent(), "research-1"))
        val beforeScene = ScreenScene(browser, browser, windowId = 4, generation = 1,
            screenType = "WEB_PAGE", semanticElements = emptyList(),
            screenshotReference = null, observedAt = 1000L, confidence = .9)
        val before = PerceptionSnapshot(beforeScene, task.id, 1000L)
        val step = (runtime.next(before) as PlannerResult.Next).step
        assertEquals(ToolCapability.BROWSER_SEARCH, step.capability)
        runtime.recordAction(step, GeneralActionResult(true), before)
        assertNotNull(runtime.pauseForBrowserAuthentication(
            context(1200L, login), 1250L))
        assertEquals(AgentRuntimeStatus.WAITING_FOR_USER_AUTH, runtime.activeTask()?.status)
        assertNull(runtime.pauseForBrowserAuthentication(context(1300L, login), 1350L))
        assertTrue(runtime.next(before) is PlannerResult.NeedClarification)
        assertEquals(GeneralVerificationStatus.UNKNOWN,
            runtime.verify(before.copy(capturedAt = 1300L)).first.status)
        assertTrue(runtime.takeVerifiedSearchOutcomes().isEmpty())

        assertTrue(runtime.resumeBrowserAuthentication(
            context(2000L, publicLines), context(2400L, publicLines), 2450L))
        assertNull(runtime.activeTask()?.authPause)
        assertFalse(runtime.resumeBrowserAuthentication(
            context(2700L, publicLines), context(3000L, publicLines), 3050L))
        val after = PerceptionSnapshot(beforeScene.copy(
            generation = 2, observedAt = 2600L, semanticElements = publicLines),
            task.id, 2600L)
        assertEquals(GeneralVerificationStatus.SUCCESS, runtime.verify(after).first.status)
        assertEquals(task.id, runtime.activeTask()?.id)
        assertEquals(1, runtime.activeTask()?.actionHistory?.size)
        assertEquals(1, runtime.activeTask()?.verifiedGoalSubsteps?.size)
        assertEquals(1, runtime.takeVerifiedSearchOutcomes().size)
        assertNull(runtime.lastCompletedTask()) // research goal is NOT declared complete
        assertNull(runtime.pauseForBrowserAuthentication(context(2900L, login), 2950L))
    }

    @Test fun aNewTurnCancelsOldAuthWaitAndCannotResumeNewTask() {
        val runtime = GeneralAgentRuntime(now = { 100L })
        val task = requireNotNull(runtime.start(77L, intent(), "original"))
        val scene = ScreenScene(browser, browser, windowId = 4, generation = 1,
            screenType = "WEB_PAGE", semanticElements = emptyList(),
            screenshotReference = null, observedAt = 1000L, confidence = .9)
        val pre = PerceptionSnapshot(scene, task.id, 1000L)
        val step = (runtime.next(pre) as PlannerResult.Next).step
        runtime.recordAction(step, GeneralActionResult(true), pre)
        assertNotNull(runtime.pauseForBrowserAuthentication(context(1200L, login), 1250L))
        runtime.start(78L, intent(), "new-goal")
        assertFalse(runtime.resumeBrowserAuthentication(
            context(2000L, publicLines), context(2400L, publicLines), 2450L))
        assertEquals("new-goal", runtime.activeTask()?.id)
        assertTrue(runtime.takeVerifiedSearchOutcomes().isEmpty())
    }
}
