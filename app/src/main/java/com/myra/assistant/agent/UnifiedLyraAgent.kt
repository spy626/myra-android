package com.myra.assistant.agent

import java.util.Locale

class UnifiedLyraAgent(private val tools: AgentToolRegistry = AgentToolRegistry()) {
    @Volatile private var active: AgentTask? = null
    @Volatile private var lastReferencedElement: SemanticElement? = null
    private val rejectedElementIds = linkedSetOf<String>()

    fun currentTask(): AgentTask? = active

    @Synchronized fun createTask(request: String, context: CurrentActivityContext?, visualAllowed: Boolean): AgentTask {
        active = active?.copy(state = AgentTaskState.CANCELLED)
        rejectedElementIds.clear()
        val goal = understandGoal(request)
        val task = AgentTask(
            originalUserRequest = request.trim(), interpretedGoal = goal,
            expectedApp = context?.packageName, state = AgentTaskState.PLANNING,
            plan = AgentPlanner.plan(goal, visualAllowed),
            observations = context?.let { listOf(AgentObservation(it, false, it.timestamp)) }.orEmpty(),
            confidence = if (goal == AgentGoalType.UNKNOWN) 0.25 else 0.8
        )
        active = task
        return task
    }

    fun resolveReference(request: String, context: CurrentActivityContext): AgentDecision {
        val normalized = normalize(request)
        val candidates = context.visibleElements.filter { it.actionable }
            .filterNot { it.id in rejectedElementIds }
        if (Regex("\\b(?:woh nahi|wo nahi|not that|doosra|dusra|other)\\b").containsMatchIn(normalized) &&
            lastReferencedElement != null
        ) {
            rejectedElementIds += lastReferencedElement!!.id
            val alternatives = candidates.filterNot { it.id == lastReferencedElement!!.id }
            return if (alternatives.size == 1) uniqueOrClarify(alternatives, "Kaunsa doosra wala?")
            else AgentDecision.Clarify("Kaunsa doosra wala?")
        }
        if (Regex("\\b(?:second|doosra|dusra|2nd)\\b").containsMatchIn(normalized)) {
            val cards = candidates.filter { it.role in setOf(SemanticRole.VIDEO_CARD, SemanticRole.VIDEO, SemanticRole.LIST_ITEM) }
                .distinctBy { it.groupId ?: it.id }.sortedWith(compareBy<SemanticElement> { it.top }.thenBy { it.left })
            return cards.getOrNull(1)?.let { lastReferencedElement = it; AgentDecision.Execute(AgentPlanStep("tap_second", ToolCapability.ACCESSIBILITY_CLICK, it.role), it) }
                ?: AgentDecision.Clarify("Kaunsa doosra wala?")
        }
        if (normalized.contains("hand wala") || normalized.contains("thumb wala")) {
            val likes = candidates.filter { it.role == SemanticRole.LIKE_CONTROL }
            return uniqueOrClarify(likes, "Kaunsa hand wala button?")
        }
        if (normalized in setOf("ye wala", "isko kholo", "click this", "click that", "ye wala dabao")) {
            val previous = lastReferencedElement?.takeIf { old -> candidates.any { it.id == old.id } }
            return previous?.let { AgentDecision.Execute(AgentPlanStep("tap_reference", ToolCapability.ACCESSIBILITY_CLICK, it.role), it) }
                ?: if (candidates.size == 1) uniqueOrClarify(candidates, "Kaunsa wala?") else AgentDecision.Clarify("Kaunsa wala?")
        }
        return AgentDecision.ObserveMore(useScreenshot = true)
    }

    @Synchronized fun recordAction(record: AgentActionRecord, updatedContext: CurrentActivityContext?): AgentDecision {
        val task = active ?: return AgentDecision.Fail("no_active_task")
        if (updatedContext != null && task.expectedApp != updatedContext.packageName) {
            active = task.copy(state = AgentTaskState.CANCELLED)
            lastReferencedElement = null
            return AgentDecision.Fail("foreground_changed")
        }
        val next = task.copy(previousActions = task.previousActions + record, state = if (record.verified == true) AgentTaskState.COMPLETED else AgentTaskState.RECOVERING)
        if (record.verified != true && record.targetId != null) rejectedElementIds += record.targetId
        active = next
        return if (record.verified == true) AgentDecision.Complete(silent = true)
        else AgentTaskPolicy.nextAfterFailure(next.copy(retryCount = next.retryCount + 1), canObserveMore = true)
    }

    @Synchronized fun invalidateForContext(context: CurrentActivityContext) {
        if (active?.expectedApp != null && active?.expectedApp != context.packageName) {
            active = active?.copy(state = AgentTaskState.CANCELLED)
            lastReferencedElement = null
            rejectedElementIds.clear()
        }
    }

    fun relevantTools(): List<ToolDefinition> = active?.plan?.map { it.capability }?.toSet()?.let(tools::relevant).orEmpty()

    private fun uniqueOrClarify(candidates: List<SemanticElement>, message: String): AgentDecision = when (candidates.size) {
        1 -> candidates.single().let { lastReferencedElement = it; AgentDecision.Execute(AgentPlanStep("tap_semantic", ToolCapability.ACCESSIBILITY_CLICK, it.role), it) }
        else -> AgentDecision.Clarify(message)
    }

    private fun understandGoal(raw: String): AgentGoalType {
        val text = normalize(raw)
        return when {
            Regex("\\b(?:scroll|niche|neeche|upar)\\b").containsMatchIn(text) -> AgentGoalType.SCROLL
            Regex("\\b(?:type|likho|write)\\b").containsMatchIn(text) -> AgentGoalType.TYPE
            Regex("^(?:send|post)(?: karo)?$").matches(text) -> AgentGoalType.SEND
            Regex("\\b(?:click|tap|dabao|kholo|open|wala|thumb|hand)\\b").containsMatchIn(text) -> AgentGoalType.TAP
            Regex("\\b(?:screen|dikh|problem|error)\\b").containsMatchIn(text) -> AgentGoalType.ANSWER_SCREEN
            else -> AgentGoalType.UNKNOWN
        }
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()
}

class AgentActionRouter(private val registry: AgentToolRegistry = AgentToolRegistry()) {
    fun select(step: AgentPlanStep, context: CurrentActivityContext?): ToolDefinition? {
        val candidates = registry.relevant(setOf(step.capability))
        return candidates.firstOrNull { tool -> !tool.foregroundRequired || context != null }
    }
}

object UnifiedLyraAgentRuntime {
    val agent = UnifiedLyraAgent()
}
