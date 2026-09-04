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

    private fun context() = CurrentActivityContext("com.android.chrome", "Chrome", "BROWSER", 1, 2, emptyList(), confidence = .8, timestamp = 3)
}
