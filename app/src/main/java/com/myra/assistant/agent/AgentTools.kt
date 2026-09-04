package com.myra.assistant.agent

enum class ToolCapability {
    OBSERVE_SCREEN, VISUAL_CHECK, FIND_ELEMENT, ACCESSIBILITY_CLICK, LONG_PRESS,
    ACCESSIBILITY_SCROLL, SWIPE, ACCESSIBILITY_TYPE, CLEAR_TEXT, PRESS_ENTER,
    ACCESSIBILITY_SCREENSHOT, GESTURE, OPEN_APP, SWITCH_APP, BACK, HOME,
    WAIT_FOR_SCREEN, BROWSER_SEARCH, WEB_SEARCH, VERIFY_SCREEN, MEMORY_READ,
    API_QUERY, CONTINUOUS_SCREEN
}
enum class ToolRisk { LOW, MEDIUM, HIGH }

data class ToolDefinition(
    val id: String,
    val capability: ToolCapability,
    val requiredPermission: String? = null,
    val foregroundRequired: Boolean = false,
    val risk: ToolRisk = ToolRisk.LOW,
    val verificationStrategy: String
)

class AgentToolRegistry(definitions: List<ToolDefinition> = defaults()) {
    private val tools = definitions.associateBy { it.id }
    fun relevant(capabilities: Set<ToolCapability>): List<ToolDefinition> =
        tools.values.filter { it.capability in capabilities }.sortedBy { it.risk.ordinal }
    fun get(id: String): ToolDefinition? = tools[id]

    companion object {
        fun defaults() = listOf(
            ToolDefinition("accessibility_click", ToolCapability.ACCESSIBILITY_CLICK, "ACCESSIBILITY", true, verificationStrategy = "semantic_state_change"),
            ToolDefinition("observe_screen", ToolCapability.OBSERVE_SCREEN, "ACCESSIBILITY", true, verificationStrategy = "fresh_context"),
            ToolDefinition("find_element", ToolCapability.FIND_ELEMENT, "ACCESSIBILITY", true, verificationStrategy = "unique_current_candidate"),
            ToolDefinition("accessibility_scroll", ToolCapability.ACCESSIBILITY_SCROLL, "ACCESSIBILITY", true, verificationStrategy = "content_or_position_change"),
            ToolDefinition("accessibility_type", ToolCapability.ACCESSIBILITY_TYPE, "ACCESSIBILITY", true, ToolRisk.MEDIUM, "field_value_matches"),
            ToolDefinition("accessibility_screenshot", ToolCapability.ACCESSIBILITY_SCREENSHOT, "ACCESSIBILITY", true, verificationStrategy = "fresh_window_bound_image"),
            ToolDefinition("gesture", ToolCapability.GESTURE, "ACCESSIBILITY", true, ToolRisk.MEDIUM, "fresh_target_then_state_change"),
            ToolDefinition("open_app", ToolCapability.OPEN_APP, foregroundRequired = false, verificationStrategy = "foreground_package_matches"),
            ToolDefinition("wait_for_screen", ToolCapability.WAIT_FOR_SCREEN, foregroundRequired = false, verificationStrategy = "new_observation"),
            ToolDefinition("browser_search", ToolCapability.BROWSER_SEARCH, foregroundRequired = false, verificationStrategy = "browser_results_visible"),
            ToolDefinition("web_search", ToolCapability.WEB_SEARCH, foregroundRequired = false, verificationStrategy = "grounded_results_returned"),
            ToolDefinition("verify_screen", ToolCapability.VERIFY_SCREEN, "ACCESSIBILITY", true, verificationStrategy = "expected_state_evidence"),
            ToolDefinition("continuous_screen", ToolCapability.CONTINUOUS_SCREEN, "MEDIA_PROJECTION", false, ToolRisk.MEDIUM, "active_projection_session")
        )
    }
}

object AgentPlanner {
    fun plan(goal: AgentGoalType, visualAllowed: Boolean): List<AgentPlanStep> = when (goal) {
        AgentGoalType.TAP -> buildList {
            add(AgentPlanStep("resolve_accessibility", ToolCapability.ACCESSIBILITY_CLICK))
            if (visualAllowed) add(AgentPlanStep("observe_visual", ToolCapability.ACCESSIBILITY_SCREENSHOT))
            if (visualAllowed) add(AgentPlanStep("fresh_visual_gesture", ToolCapability.GESTURE))
        }
        AgentGoalType.SCROLL -> listOf(AgentPlanStep("scroll_current_window", ToolCapability.ACCESSIBILITY_SCROLL))
        AgentGoalType.TYPE -> listOf(AgentPlanStep("type_owned_field", ToolCapability.ACCESSIBILITY_TYPE))
        AgentGoalType.SEND -> listOf(AgentPlanStep("submit_owned_draft", ToolCapability.ACCESSIBILITY_CLICK, SemanticRole.SEND))
        AgentGoalType.ANSWER_SCREEN -> if (visualAllowed) listOf(AgentPlanStep("observe_visual", ToolCapability.ACCESSIBILITY_SCREENSHOT)) else emptyList()
        AgentGoalType.OPEN_APP -> listOf(AgentPlanStep("open_app", ToolCapability.OPEN_APP))
        AgentGoalType.NAVIGATE -> listOf(AgentPlanStep("navigate", ToolCapability.BACK))
        AgentGoalType.BROWSER_SEARCH -> listOf(
            AgentPlanStep("choose_search_tool", ToolCapability.BROWSER_SEARCH),
            AgentPlanStep("wait_for_results", ToolCapability.WAIT_FOR_SCREEN),
            AgentPlanStep("observe_results", ToolCapability.OBSERVE_SCREEN),
            AgentPlanStep("verify_results", ToolCapability.VERIFY_SCREEN)
        )
        AgentGoalType.WEB_SEARCH -> listOf(
            AgentPlanStep("search_current_information", ToolCapability.WEB_SEARCH),
            AgentPlanStep("verify_grounded_results", ToolCapability.VERIFY_SCREEN)
        )
        AgentGoalType.UNKNOWN -> emptyList()
    }
}

object AgentVerification {
    fun semanticStateChanged(before: CurrentActivityContext, after: CurrentActivityContext, expectedRole: SemanticRole?): Boolean {
        if (before.packageName != after.packageName || before.windowId != after.windowId) return true
        if (after.generation != before.generation) return true
        if (expectedRole == null) return before.visibleElements != after.visibleElements
        val old = before.visibleElements.filter { it.role == expectedRole }
        val new = after.visibleElements.filter { it.role == expectedRole }
        return old != new
    }
}
