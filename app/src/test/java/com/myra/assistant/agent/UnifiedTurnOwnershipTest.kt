package com.myra.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedTurnOwnershipTest {
    @Test fun chrome_words_in_architecture_discussion_authorize_zero_actions() {
        val result = UnifiedTurnInterpreter.interpret(
            "Main soch raha hun ki agar Chrome mein koi article open ho to LYRA screen dekh ke samajh sake.", null
        )
        assertEquals(TurnIntent.CONVERSATION, result.intent)
        assertFalse(result.authorizesPhoneActions)
    }

    @Test fun youtube_control_words_in_discussion_authorize_zero_actions() {
        val result = UnifiedTurnInterpreter.interpret(
            "LYRA ko YouTube ke like comment subscribe buttons screen dekh ke samajhne chahiye.", null
        )
        assertEquals(TurnIntent.CONVERSATION, result.intent)
        assertFalse(result.authorizesPhoneActions)
    }

    @Test fun explicit_open_is_an_action() {
        val result = UnifiedTurnInterpreter.interpret("Chrome kholo.", null)
        assertEquals(TurnIntent.ACTION_REQUEST, result.intent)
        assertTrue(result.authorizesPhoneActions)
    }

    @Test fun explicit_current_search_is_a_planned_goal() {
        val result = UnifiedTurnInterpreter.interpret("Google mein search karo aaj koi new AI aaya hai kya.", null)
        assertEquals(TurnIntent.MULTI_STEP_GOAL, result.intent)
        assertTrue(result.authorizesPhoneActions)
    }

    @Test fun speculative_current_information_statement_stays_conversation() {
        val result = UnifiedTurnInterpreter.interpret("Aaj koi naya AI aaya hoga shayad.", null)
        assertEquals(TurnIntent.CONVERSATION, result.intent)
        assertFalse(result.authorizesPhoneActions)
    }

    @Test fun action_follow_up_uses_working_task_not_name_correction() {
        val context = WorkingTaskContext(lastRequestedAction = "center article click", lastVerifiedSuccess = false)
        assertEquals(TurnIntent.FOLLOW_UP, UnifiedTurnInterpreter.interpret("Abhi nahi hua na?", context).intent)
    }

    @Test fun one_core_does_not_create_tasks_for_conversation() {
        val agent = UnifiedLyraAgent()
        val decision = agent.acceptTurn("YouTube open behavior useful hona chahiye.", context(), true)
        assertEquals(TurnIntent.CONVERSATION, decision.intent)
        assertEquals(null, agent.currentTask())
    }

    @Test fun browser_search_builds_observe_and_verify_plan() {
        val agent = UnifiedLyraAgent()
        val decision = agent.acceptTurn("Google mein search karo Android AI news", context(), true)
        assertEquals(TurnIntent.MULTI_STEP_GOAL, decision.intent)
        assertEquals(AgentGoalType.BROWSER_SEARCH, agent.currentTask()?.interpretedGoal)
        assertTrue(agent.currentTask()!!.plan.any { it.capability == ToolCapability.OBSERVE_SCREEN })
        assertTrue(agent.currentTask()!!.plan.any { it.capability == ToolCapability.VERIFY_SCREEN })
    }

    @Test fun browser_search_adapter_extracts_only_goal_query() {
        val request = BrowserSearchRequestParser.parse("Google mein search karo aaj koi new AI aaya hai kya")
        assertEquals("aaj koi new AI aaya hai kya", request?.query)
    }

    @Test fun nicheJaoCreatesScrollRuntimeTaskForTheSameTurn() {
        val agent = UnifiedLyraAgent()
        val decision = agent.acceptTurn("Niche jao", context(), true, turnId = 7L)
        val runtime = GeneralAgentRuntimeStore.runtime.activeTask()!!
        assertEquals(TurnIntent.ACTION_REQUEST, decision.intent)
        assertEquals(7L, runtime.turnId)
        assertEquals(agent.currentTask()?.id, runtime.id)
        assertEquals("DOWN", runtime.intent.parameters["direction"])
        assertEquals(setOf(ToolCapability.ACCESSIBILITY_SCROLL), runtime.intent.requiredCapabilities)
    }

    @Test fun thodaAurCreatesANewTurnTaskAndInheritsSuccessfulScrollDirection() {
        val completed = CompletedTaskContext(
            taskId = "old", goal = "SCROLL", action = ToolCapability.ACCESSIBILITY_SCROLL.name,
            query = null, destination = null, executor = ToolCapability.ACCESSIBILITY_SCROLL.name,
            observedOutcome = "screen moved", completionState = TaskCompletionState.SUCCESS,
            completedAt = 1L, scrollDirection = "DOWN"
        )
        val working = WorkingTaskContext(lastCompletedTask = completed)
        val decision = UnifiedTurnInterpreter.interpret("Thoda aur", working)
        val structured = UnifiedLyraAgent().toStructuredIntent("Thoda aur", decision, working = working)
        assertEquals(TurnIntent.ACTION_REQUEST, decision.intent)
        assertTrue(decision.authorizesPhoneActions)
        assertEquals("DOWN", structured.parameters["direction"])
        assertEquals(setOf(ToolCapability.ACCESSIBILITY_SCROLL), structured.requiredCapabilities)
    }

    @Test fun thodaAurWithoutSuccessfulScrollContextExecutesNoTools() {
        val decision = UnifiedTurnInterpreter.interpret("Thoda aur", WorkingTaskContext())
        assertEquals(TurnIntent.CONVERSATION, decision.intent)
        assertFalse(decision.authorizesPhoneActions)
    }

    @Test fun thodaAurWithExplicitDirectionIsAnActionWithoutGuessingOldContext() {
        val decision = UnifiedTurnInterpreter.interpret("Thoda aur niche", WorkingTaskContext())
        assertEquals(TurnIntent.ACTION_REQUEST, decision.intent)
        assertTrue(decision.authorizesPhoneActions)
        val structured = UnifiedLyraAgent().toStructuredIntent("Thoda aur niche", decision)
        assertEquals(setOf(ToolCapability.ACCESSIBILITY_SCROLL), structured.requiredCapabilities)
        assertEquals("DOWN", structured.parameters["direction"])
    }

    @Test fun actionRequestSearchCreatesBrowserSearchPlanInsteadOfUnknownTask() {
        val agent = UnifiedLyraAgent()
        val decision = agent.acceptTurn("Search a new AI", context(), true, turnId = 18L)
        assertEquals(TurnIntent.ACTION_REQUEST, decision.intent)
        assertEquals(AgentGoalType.BROWSER_SEARCH, agent.currentTask()?.interpretedGoal)
        assertTrue(GeneralAgentRuntimeStore.runtime.activeTask()!!.intent.requiredCapabilities.contains(ToolCapability.BROWSER_SEARCH))
        assertTrue(agent.currentTask()!!.plan.isNotEmpty())
    }

    @Test fun devanagariDirectionKeepsMeaningAndCreatesScrollAction() {
        val decision = UnifiedTurnInterpreter.interpret("नीचे जाओ", null)
        assertEquals(TurnIntent.ACTION_REQUEST, decision.intent)
        assertTrue(decision.authorizesPhoneActions)
        val structured = UnifiedLyraAgent().toStructuredIntent("नीचे जाओ", decision)
        assertEquals("DOWN", structured.parameters["direction"])
    }

    @Test fun devanagariContinuationUsesOnlyVerifiedScrollContext() {
        val completed = CompletedTaskContext(
            taskId = "old", goal = "SCROLL", action = ToolCapability.ACCESSIBILITY_SCROLL.name,
            query = null, destination = null, executor = ToolCapability.ACCESSIBILITY_SCROLL.name,
            observedOutcome = "viewport movement proven", completionState = TaskCompletionState.SUCCESS,
            completedAt = 1L, scrollDirection = "DOWN"
        )
        val decision = UnifiedTurnInterpreter.interpret("और थोड़ा", WorkingTaskContext(lastCompletedTask = completed))
        assertEquals(TurnIntent.ACTION_REQUEST, decision.intent)
        assertTrue(decision.authorizesPhoneActions)
    }

    @Test fun hindiDiscussionContainingDirectionExecutesNoTools() {
        val decision = UnifiedTurnInterpreter.interpret("मैं सोच रहा हूं कि नीचे जाने का फीचर कैसा होना चाहिए", null)
        assertEquals(TurnIntent.CONVERSATION, decision.intent)
        assertFalse(decision.authorizesPhoneActions)
    }

    private fun context() = CurrentActivityContext("com.android.chrome", "Chrome", "BROWSER", 1, 2, emptyList(), confidence = .8, timestamp = 3)
}
