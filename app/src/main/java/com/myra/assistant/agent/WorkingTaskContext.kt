package com.myra.assistant.agent

data class WorkingTaskContext(
    val taskId: String? = null,
    val conversationTopic: String? = null,
    val currentGoal: String? = null,
    val activeExternalApp: String? = null,
    val expectedApp: String? = null,
    val screenType: String? = null,
    val screenGeneration: Long = 0,
    val windowGeneration: Long = 0,
    val currentStep: String? = null,
    val planRevision: Int = 0,
    val lastRequestedAction: String? = null,
    val previousActionTarget: String? = null,
    val expectedOutcome: String? = null,
    val lastObservedOutcome: String? = null,
    val lastVerifiedSuccess: Boolean? = null,
    val currentReference: String? = null,
    val unresolvedReference: String? = null,
    val rejectedTargets: Set<String> = emptySet(),
    val recoveryCount: Int = 0,
    val currentModal: ModalKind = ModalKind.NONE,
    val taskStatus: AgentRuntimeStatus? = null,
    val contextGeneration: Long = 0,
    val searchQuery: String? = null,
    val resolvedDestination: SearchDestination? = null,
    val selectedExecutor: String? = null,
    val actionStartedAt: Long = 0,
    val completionState: TaskCompletionState? = null,
    val lastCompletedTask: CompletedTaskContext? = null,
    val updatedAt: Long = 0
)

data class CompletedTaskContext(
    val taskId: String?,
    val goal: String?,
    val action: String?,
    val query: String?,
    val destination: SearchDestination?,
    val executor: String?,
    val observedOutcome: String,
    val completionState: TaskCompletionState,
    val completedAt: Long,
    val scrollDirection: String? = null
)

enum class TaskCompletionState { EXECUTING, SUCCESS, FAILURE, UNKNOWN }

class WorkingTaskContextStore(private val now: () -> Long = System::currentTimeMillis) {
    @Volatile private var value = WorkingTaskContext()
    fun snapshot(): WorkingTaskContext = value

    @Synchronized fun syncRuntime(task: GeneralRuntimeTask, scene: ScreenScene? = null) {
        value = value.copy(
            taskId = task.id,
            currentGoal = task.intent.interpretedGoal,
            activeExternalApp = scene?.externalForegroundPackage ?: value.activeExternalApp,
            expectedApp = task.intent.relevantApp ?: value.expectedApp,
            screenType = scene?.screenType ?: value.screenType,
            screenGeneration = scene?.generation ?: value.screenGeneration,
            windowGeneration = scene?.windowId?.toLong() ?: value.windowGeneration,
            currentStep = task.currentStep?.id,
            planRevision = task.planRevision,
            previousActionTarget = task.actionHistory.lastOrNull()?.targetId ?: value.previousActionTarget,
            expectedOutcome = task.currentStep?.expectedOutcome?.summary ?: value.expectedOutcome,
            rejectedTargets = task.rejectedTargets,
            recoveryCount = task.recoveryCount,
            currentModal = scene?.modal ?: value.currentModal,
            taskStatus = task.status,
            contextGeneration = value.contextGeneration + 1,
            updatedAt = now()
        )
    }

    @Synchronized fun onTurn(decision: AgentTurnDecision, task: AgentTask?, scene: ScreenScene?): WorkingTaskContext {
        value = if (decision.intent in setOf(TurnIntent.ACTION_REQUEST, TurnIntent.MULTI_STEP_GOAL)) {
            WorkingTaskContext(
                taskId = task?.id, currentGoal = decision.goal,
                activeExternalApp = scene?.externalForegroundPackage ?: scene?.packageName,
                expectedApp = task?.expectedApp,
                screenType = scene?.screenType,
                screenGeneration = scene?.generation ?: 0,
                windowGeneration = scene?.windowId?.toLong() ?: 0,
                lastRequestedAction = decision.goal,
                expectedOutcome = task?.expectedResult,
                currentModal = scene?.modal ?: ModalKind.NONE,
                taskStatus = AgentRuntimeStatus.UNDERSTANDING,
                contextGeneration = value.contextGeneration + 1,
                lastCompletedTask = value.lastCompletedTask,
                updatedAt = now()
            )
        } else if (decision.intent in setOf(TurnIntent.CONVERSATION, TurnIntent.QUESTION)) {
            WorkingTaskContext(
                conversationTopic = decision.goal,
                activeExternalApp = scene?.externalForegroundPackage ?: value.activeExternalApp,
                screenGeneration = scene?.generation ?: value.screenGeneration,
                windowGeneration = scene?.windowId?.toLong() ?: value.windowGeneration,
                lastCompletedTask = value.lastCompletedTask,
                contextGeneration = value.contextGeneration + 1,
                updatedAt = now()
            )
        } else value.copy(updatedAt = now())
        return value
    }

    @Synchronized fun recordOutcome(
        observed: String,
        verified: Boolean,
        rejectedTarget: String? = null,
        runtimeOwnsRecoveryCount: Boolean = false
    ) {
        value = value.copy(
            lastObservedOutcome = observed, lastVerifiedSuccess = verified,
            previousActionTarget = rejectedTarget ?: value.previousActionTarget,
            rejectedTargets = rejectedTarget?.let { value.rejectedTargets + it } ?: value.rejectedTargets,
            recoveryCount = if (verified) 0 else if (runtimeOwnsRecoveryCount) value.recoveryCount else value.recoveryCount + 1,
            updatedAt = now()
        )
    }

    @Synchronized fun recordReference(elementId: String) {
        value = value.copy(currentReference = elementId, unresolvedReference = null, updatedAt = now())
    }

    @Synchronized fun beginSearch(query: String, destination: SearchDestination, executor: String, expected: String) {
        value = value.copy(
            searchQuery = query, resolvedDestination = destination, selectedExecutor = executor,
            actionStartedAt = now(), expectedOutcome = expected, lastObservedOutcome = null,
            lastVerifiedSuccess = null, completionState = TaskCompletionState.EXECUTING, updatedAt = now()
        )
    }

    @Synchronized fun completeSearch(observed: String, state: TaskCompletionState): CompletedTaskContext {
        val completedAt = now()
        val completed = CompletedTaskContext(
            value.taskId, value.currentGoal, value.lastRequestedAction, value.searchQuery,
            value.resolvedDestination, value.selectedExecutor, observed, state, completedAt
        )
        // Terminal task details are history, not active routing context. In particular,
        // a completed YouTube destination must never bias a later generic search.
        value = WorkingTaskContext(
            conversationTopic = value.conversationTopic,
            activeExternalApp = value.activeExternalApp,
            screenGeneration = value.screenGeneration,
            windowGeneration = value.windowGeneration,
            lastCompletedTask = completed,
            contextGeneration = value.contextGeneration + 1,
            updatedAt = completedAt
        )
        return completed
    }

    @Synchronized fun completeRuntime(task: GeneralRuntimeTask, observed: String, state: TaskCompletionState): CompletedTaskContext {
        val completedAt = now()
        val completed = CompletedTaskContext(
            task.id, task.intent.interpretedGoal,
            task.actionHistory.lastOrNull()?.capability?.name ?: task.currentStep?.capability?.name,
            task.intent.parameters["query"],
            task.intent.parameters["destination"]?.let { runCatching { SearchDestination.valueOf(it) }.getOrNull() },
            task.actionHistory.lastOrNull()?.capability?.name,
            observed, state, completedAt,
            task.intent.parameters["direction"]
        )
        value = WorkingTaskContext(
            conversationTopic = value.conversationTopic,
            activeExternalApp = value.activeExternalApp,
            screenGeneration = value.screenGeneration,
            windowGeneration = value.windowGeneration,
            lastCompletedTask = completed,
            contextGeneration = value.contextGeneration + 1,
            updatedAt = completedAt
        )
        return completed
    }

    @Synchronized fun invalidateIfExternalAppChanged(packageName: String, generation: Long) {
        if (packageName in setOf("com.myra.assistant", "com.android.systemui")) {
            value = value.copy(screenGeneration = generation, updatedAt = now())
            return
        }
        if (value.activeExternalApp != null && value.activeExternalApp != packageName) {
            value = WorkingTaskContext(
                conversationTopic = value.conversationTopic,
                activeExternalApp = packageName,
                screenGeneration = generation,
                contextGeneration = value.contextGeneration + 1,
                lastCompletedTask = value.lastCompletedTask,
                updatedAt = now()
            )
        }
    }

    @Synchronized fun clearTask() {
        value = WorkingTaskContext(
            conversationTopic = value.conversationTopic,
            activeExternalApp = value.activeExternalApp,
            screenGeneration = value.screenGeneration,
            windowGeneration = value.windowGeneration,
            lastCompletedTask = value.lastCompletedTask,
            contextGeneration = value.contextGeneration + 1,
            updatedAt = now()
        )
    }
}
object WorkingTaskRuntime { val store = WorkingTaskContextStore() }
