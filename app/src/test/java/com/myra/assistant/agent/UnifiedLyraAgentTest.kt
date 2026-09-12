package com.myra.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedLyraAgentTest {
    @Test fun hand_reference_selects_the_only_like_control() {
        val like = element("like", SemanticRole.LIKE_CONTROL, top = 100)
        val decision = UnifiedLyraAgent().resolveReference("ye hand wala dabao", context(listOf(like)))
        assertEquals(like, (decision as AgentDecision.Execute).target)
    }

    @Test fun second_reference_uses_unique_ordered_video_cards() {
        val first = element("one", SemanticRole.VIDEO_CARD, top = 100, group = "one")
        val second = element("two", SemanticRole.VIDEO_CARD, top = 300, group = "two")
        val decision = UnifiedLyraAgent().resolveReference("second wala", context(listOf(second, first)))
        assertEquals("two", (decision as AgentDecision.Execute).target?.id)
    }

    @Test fun ambiguous_reference_clarifies_instead_of_tapping() {
        val decision = UnifiedLyraAgent().resolveReference(
            "click this", context(listOf(element("a", SemanticRole.BUTTON), element("b", SemanticRole.BUTTON)))
        )
        assertTrue(decision is AgentDecision.Clarify)
    }

    @Test fun package_change_invalidates_task_and_reference() {
        val agent = UnifiedLyraAgent()
        agent.createTask("second wala", context(emptyList()), true)
        agent.invalidateForContext(context(emptyList(), packageName = "com.android.chrome"))
        assertEquals(AgentTaskState.CANCELLED, agent.currentTask()?.state)
    }

    @Test fun verification_failure_is_bounded() {
        val exhausted = AgentTask("x", "tap", AgentGoalType.TAP, "pkg", retryCount = 2)
        assertTrue(AgentTaskPolicy.nextAfterFailure(exhausted, true) is AgentDecision.Fail)
        val retry = exhausted.copy(retryCount = 0)
        assertTrue(AgentTaskPolicy.nextAfterFailure(retry, true) is AgentDecision.ObserveMore)
    }

    @Test fun correction_excludes_previous_failed_target() {
        val agent = UnifiedLyraAgent()
        val first = element("one", SemanticRole.VIDEO_CARD, top = 100)
        val second = element("two", SemanticRole.VIDEO_CARD, top = 300)
        val context = context(listOf(first, second))
        agent.createTask("ye wala", context, true)
        val chosen = agent.resolveReference("second wala", context) as AgentDecision.Execute
        agent.recordAction(AgentActionRecord("accessibility_click", chosen.target?.id, true, false, 2), context)
        val correction = agent.resolveReference("woh nahi doosra", context) as AgentDecision.Execute
        assertEquals("one", correction.target?.id)
    }

    @Test fun action_router_requires_foreground_for_accessibility_tool() {
        val step = AgentPlanStep("tap", ToolCapability.ACCESSIBILITY_CLICK)
        assertEquals(null, AgentActionRouter().select(step, null))
        assertEquals("accessibility_click", AgentActionRouter().select(step, context(emptyList()))?.id)
    }

    @Test fun tool_registry_exposes_only_relevant_capabilities() {
        val tools = AgentToolRegistry().relevant(setOf(ToolCapability.ACCESSIBILITY_CLICK))
        assertEquals(listOf("accessibility_click"), tools.map { it.id })
        assertFalse(tools.any { it.capability == ToolCapability.CONTINUOUS_SCREEN })
    }

    private fun element(id: String, role: SemanticRole, top: Int = 0, group: String? = null) =
        SemanticElement(id, role, id, 0, top, 100, top + 80, true, groupId = group)

    private fun context(elements: List<SemanticElement>, packageName: String = "com.google.android.youtube") =
        CurrentActivityContext(packageName, null, "UNKNOWN", 1, 1, elements, confidence = .9, timestamp = 1)
}
