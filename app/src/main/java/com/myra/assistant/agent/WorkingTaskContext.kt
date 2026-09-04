package com.myra.assistant.agent

data class WorkingTaskContext(
    val taskId: String? = null,
    val conversationTopic: String? = null,
    val currentGoal: String? = null,
    val activeExternalApp: String? = null,
    val screenGeneration: Long = 0,
    val lastRequestedAction: String? = null,
    val expectedOutcome: String? = null,
    val lastObservedOutcome: String? = null,
    val lastVerifiedSuccess: Boolean? = null,
    val currentReference: String? = null,
    val unresolvedReference: String? = null,
    val rejectedTargets: Set<String> = emptySet(),
    val recoveryCount: Int = 0,
    val updatedAt: Long = 0
)

class WorkingTaskContextStore(private val now: () -> Long = System::currentTimeMillis) {
    @Volatile private var value = WorkingTaskContext()
    fun snapshot(): WorkingTaskContext = value

    @Synchronized fun onTurn(decision: AgentTurnDecision, task: AgentTask?, scene: ScreenScene?): WorkingTaskContext {
        value = if (decision.intent in setOf(TurnIntent.ACTION_REQUEST, TurnIntent.MULTI_STEP_GOAL)) {
            WorkingTaskContext(
                taskId = task?.id, currentGoal = decision.goal,
                activeExternalApp = scene?.externalForegroundPackage ?: scene?.packageName,
                screenGeneration = scene?.generation ?: 0,
                lastRequestedAction = decision.goal,
                expectedOutcome = task?.expectedResult,
                updatedAt = now()
            )
        } else value.copy(conversationTopic = decision.goal.takeIf { decision.intent in setOf(TurnIntent.CONVERSATION, TurnIntent.QUESTION) }, updatedAt = now())
        return value
    }

    @Synchronized fun recordOutcome(observed: String, verified: Boolean, rejectedTarget: String? = null) {
        value = value.copy(
            lastObservedOutcome = observed, lastVerifiedSuccess = verified,
            rejectedTargets = rejectedTarget?.let { value.rejectedTargets + it } ?: value.rejectedTargets,
            recoveryCount = if (verified) 0 else value.recoveryCount + 1, updatedAt = now()
        )
    }

    @Synchronized fun invalidateIfExternalAppChanged(packageName: String, generation: Long) {
        if (packageName in setOf("com.myra.assistant", "com.android.systemui")) {
            value = value.copy(screenGeneration = generation, updatedAt = now())
            return
        }
        if (value.activeExternalApp != null && value.activeExternalApp != packageName) {
            value = WorkingTaskContext(conversationTopic = value.conversationTopic, activeExternalApp = packageName, screenGeneration = generation, updatedAt = now())
        }
    }

    @Synchronized fun clearTask() { value = WorkingTaskContext(conversationTopic = value.conversationTopic, updatedAt = now()) }
}

object WorkingTaskRuntime { val store = WorkingTaskContextStore() }
