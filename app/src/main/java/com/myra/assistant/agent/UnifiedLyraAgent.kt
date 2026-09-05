package com.myra.assistant.agent

import java.util.Locale

class UnifiedLyraAgent(private val tools: AgentToolRegistry = AgentToolRegistry()) {
    @Volatile private var active: AgentTask? = null
    @Volatile private var lastReferencedElement: SemanticElement? = null
    private val rejectedElementIds = linkedSetOf<String>()

    fun currentTask(): AgentTask? = active

    /** First and authoritative owner of every completed user turn. */
    @Synchronized fun acceptTurn(request: String, context: CurrentActivityContext?, visualAllowed: Boolean, turnId: Long = 0L): AgentTurnDecision {
        val working = WorkingTaskRuntime.store.snapshot()
        val decision = UnifiedTurnInterpreter.interpret(request, working, context)
        val scene = context?.let { ScreenSceneFactory.from(it, working.activeExternalApp) }
        val task = if (decision.intent in setOf(TurnIntent.ACTION_REQUEST, TurnIntent.MULTI_STEP_GOAL)) {
            createTask(request, context, visualAllowed, decision)
        } else if (decision.intent in setOf(TurnIntent.FOLLOW_UP, TurnIntent.CORRECTION, TurnIntent.CLARIFICATION)) {
            active
        } else {
            active = active?.copy(state = AgentTaskState.CANCELLED)
            active = null
            null
        }
        WorkingTaskRuntime.store.onTurn(decision, task, scene)
        val structured = toStructuredIntent(request, decision, task, working)
        if (decision.authorizesPhoneActions) {
            val runtime = GeneralAgentRuntimeStore.runtime
            val runtimeTask = runtime.start(turnId, structured, task?.id)
            val perception = if (runtimeTask != null && scene != null) {
                PerceptionSnapshot(scene, runtimeTask.id, scene.observedAt)
            } else null
            // Production execution owner asks the planner for the next step after it has
            // attached executor parameters (query, direction, destination). Starting here
            // preserves one authoritative task without prematurely selecting a legacy path.
        } else if (decision.intent in setOf(TurnIntent.CONVERSATION, TurnIntent.QUESTION, TurnIntent.CANCEL)) {
            GeneralAgentRuntimeStore.runtime.cancel()
        }
        return decision
    }

    fun toStructuredIntent(
        request: String,
        decision: AgentTurnDecision,
        task: AgentTask? = null,
        working: WorkingTaskContext? = WorkingTaskRuntime.store.snapshot()
    ): StructuredAgentIntent {
        val goal = task?.interpretedGoal ?: understandGoal(request, decision)
        val semanticTarget = SemanticTargetRequestParser.parse(request, working)
        val capabilities = when (goal) {
            AgentGoalType.TAP -> setOf(ToolCapability.FIND_ELEMENT, ToolCapability.ACCESSIBILITY_CLICK)
            AgentGoalType.SCROLL -> setOf(ToolCapability.ACCESSIBILITY_SCROLL)
            AgentGoalType.TYPE -> setOf(ToolCapability.FIND_ELEMENT, ToolCapability.ACCESSIBILITY_TYPE)
            AgentGoalType.SEND -> setOf(ToolCapability.FIND_ELEMENT, ToolCapability.ACCESSIBILITY_CLICK)
            AgentGoalType.OPEN_APP -> setOf(ToolCapability.OPEN_APP)
            AgentGoalType.NAVIGATE -> setOf(ToolCapability.BACK)
            AgentGoalType.BROWSER_SEARCH -> setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.OBSERVE_SCREEN, ToolCapability.VERIFY_SCREEN)
            AgentGoalType.WEB_SEARCH -> setOf(ToolCapability.WEB_SEARCH, ToolCapability.OBSERVE_SCREEN, ToolCapability.VERIFY_SCREEN)
            AgentGoalType.ANSWER_SCREEN -> setOf(ToolCapability.OBSERVE_SCREEN)
            AgentGoalType.UNKNOWN -> emptySet()
        }
        return StructuredAgentIntent(
            turnIntent = decision.intent,
            originalUtterance = request,
            interpretedGoal = decision.goal,
            requiresAction = decision.authorizesPhoneActions,
            targetDescription = request.takeIf { decision.authorizesPhoneActions },
            roleHint = semanticTarget.roleHint,
            textHint = when (goal) {
                AgentGoalType.TAP -> semanticTarget.textHint
                else -> BrowserSearchRequestParser.parse(request)?.query
            },
            spatialHint = semanticTarget.spatialHint?.name,
            ordinalHint = semanticTarget.ordinal,
            requiredCapabilities = capabilities,
            parameters = when (goal) {
                AgentGoalType.SCROLL -> mapOf("direction" to resolveScrollDirection(request, working))
                AgentGoalType.TAP -> buildMap {
                    semanticTarget.roleHint?.let { put("role", it.name) }
                    semanticTarget.textHint?.let { put("text", it) }
                    semanticTarget.spatialHint?.let { put("spatial", it.name) }
                    semanticTarget.ordinal?.let { put("ordinal", it.toString()) }
                    semanticTarget.relativeToElementId?.let { put("reference", it) }
                }
                else -> BrowserSearchRequestParser.parse(request)?.let { mapOf("query" to it.query) }.orEmpty()
            },
            confidence = decision.confidence,
            needsClarification = decision.intent == TurnIntent.CLARIFICATION
        )
    }

    private fun resolveScrollDirection(request: String, working: WorkingTaskContext?): String {
        val normalized = normalize(request)
        return when {
            Regex("\\b(?:up|upar|upper|ऊपर)\\b").containsMatchIn(normalized) -> "UP"
            Regex("\\b(?:down|niche|neeche|नीचे)\\b").containsMatchIn(normalized) -> "DOWN"
            else -> working?.lastCompletedTask?.scrollDirection ?: "DOWN"
        }
    }

    @Synchronized fun createTask(
        request: String,
        context: CurrentActivityContext?,
        visualAllowed: Boolean,
        turnDecision: AgentTurnDecision? = null
    ): AgentTask {
        active = active?.copy(state = AgentTaskState.CANCELLED)
        rejectedElementIds.clear()
        val goal = understandGoal(request, turnDecision)
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
        if (context.packageName in setOf("com.myra.assistant", "com.android.systemui")) return
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

    private fun understandGoal(raw: String, decision: AgentTurnDecision? = null): AgentGoalType {
        val text = normalize(raw)
        val semantic = SemanticCapabilityParser.parse(raw, WorkingTaskRuntime.store.snapshot())
        return when {
            decision?.goal == "SCROLL" -> AgentGoalType.SCROLL
            decision?.authorizesPhoneActions == true && BrowserSearchRequestParser.parse(raw) != null -> AgentGoalType.BROWSER_SEARCH
            decision?.intent == TurnIntent.MULTI_STEP_GOAL -> AgentGoalType.WEB_SEARCH
            semantic.predicate == SemanticPredicate.OPEN_TARGET -> AgentGoalType.TAP
            semantic.predicate == SemanticPredicate.NAVIGATE_BACK -> AgentGoalType.NAVIGATE
            semantic.predicate == SemanticPredicate.MOVE_VIEWPORT -> AgentGoalType.SCROLL
            decision?.authorizesPhoneActions == true && SemanticCapabilityParser.containsStructuredTranscriptArtifact(raw) -> AgentGoalType.TAP
            Regex("\\b(?:type|likho|write)\\b").containsMatchIn(text) -> AgentGoalType.TYPE
            Regex("^(?:send|post)(?: karo)?$").matches(text) -> AgentGoalType.SEND
            Regex("\\b(?:back|peeche|piche|वापस|पीछे)\\b").containsMatchIn(text) -> AgentGoalType.NAVIGATE
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
