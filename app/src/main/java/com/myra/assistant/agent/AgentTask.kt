package com.myra.assistant.agent

import java.util.UUID

enum class AgentTaskState { UNDERSTANDING, OBSERVING, PLANNING, ACTING, VERIFYING, RECOVERING, WAITING_FOR_USER, COMPLETED, FAILED, CANCELLED }
enum class AgentGoalType { ANSWER_SCREEN, TAP, SCROLL, TYPE, SEND, OPEN_APP, NAVIGATE, BROWSER_SEARCH, WEB_SEARCH, UNKNOWN }

data class AgentObservation(val context: CurrentActivityContext, val screenshotUsed: Boolean, val observedAt: Long)
data class AgentActionRecord(
    val toolId: String,
    val targetId: String?,
    val accepted: Boolean,
    val verified: Boolean?,
    val timestamp: Long,
    val beforeGeneration: Long? = null,
    val afterGeneration: Long? = null,
    val failureReason: String? = null
)
data class AgentPlanStep(val id: String, val capability: ToolCapability, val targetRole: SemanticRole? = null, val direction: String? = null)

data class AgentTask(
    val id: String = UUID.randomUUID().toString(),
    val originalUserRequest: String,
    val interpretedGoal: AgentGoalType,
    val expectedApp: String?,
    val state: AgentTaskState = AgentTaskState.UNDERSTANDING,
    val currentStep: Int = 0,
    val plan: List<AgentPlanStep> = emptyList(),
    val observations: List<AgentObservation> = emptyList(),
    val previousActions: List<AgentActionRecord> = emptyList(),
    val expectedResult: String? = null,
    val retryCount: Int = 0,
    val confidence: Double = 0.0
)

sealed interface AgentDecision {
    data class Execute(val step: AgentPlanStep, val target: SemanticElement? = null) : AgentDecision
    data class ObserveMore(val useScreenshot: Boolean) : AgentDecision
    data class Clarify(val message: String) : AgentDecision
    data class Complete(val silent: Boolean = true) : AgentDecision
    data class Fail(val reason: String) : AgentDecision
}

object AgentTaskPolicy {
    const val MAX_SAFE_RETRIES = 2
    fun nextAfterFailure(task: AgentTask, canObserveMore: Boolean): AgentDecision = when {
        task.retryCount >= MAX_SAFE_RETRIES -> AgentDecision.Fail("safe_retry_limit")
        canObserveMore -> AgentDecision.ObserveMore(useScreenshot = true)
        else -> AgentDecision.Clarify("Kaunsa wala?")
    }
}

object AgentRecoveryPlanner {
    fun afterVerification(task: AgentTask, record: AgentActionRecord, scene: ScreenScene?): AgentDecision {
        if (record.verified == true) return AgentDecision.Complete(silent = true)
        if (scene?.modal != null && scene.modal != ModalKind.NONE) return AgentDecision.ObserveMore(useScreenshot = true)
        return AgentTaskPolicy.nextAfterFailure(task.copy(retryCount = task.retryCount + 1), canObserveMore = true)
    }
}
