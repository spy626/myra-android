package com.myra.assistant.agent

import java.util.UUID

enum class AgentRuntimeStatus {
    CREATED, UNDERSTANDING, OBSERVING, PLANNING, READY_TO_ACT, ACTING,
    WAITING_FOR_RESULT, VERIFYING, RECOVERING, NEEDS_CLARIFICATION,
    COMPLETED, FAILED, CANCELLED
}

enum class ActionCategory {
    PHONE_CONTROL, SCREEN_PERCEPTION, BROWSER, WEB_SEARCH, FILES, MEMORY,
    DEVICE, MEDIA, NOTIFICATIONS, COMMUNICATION, MAPS, APIS, CONNECTORS, SYSTEM, SAFETY
}

data class StructuredAgentIntent(
    val turnIntent: TurnIntent,
    val originalUtterance: String,
    val interpretedGoal: String,
    val requiresAction: Boolean,
    val targetDescription: String? = null,
    val roleHint: SemanticRole? = null,
    val textHint: String? = null,
    val spatialHint: String? = null,
    val ordinalHint: Int? = null,
    val relevantApp: String? = null,
    val desiredEndState: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val requiredCapabilities: Set<ToolCapability> = emptySet(),
    val confidence: Double,
    val needsClarification: Boolean = false
)

data class PerceptionSnapshot(
    val scene: ScreenScene,
    val taskId: String?,
    val capturedAt: Long,
    val sourceGeneration: Long = scene.generation
) {
    fun isCompatibleWith(other: PerceptionSnapshot): Boolean =
        scene.externalForegroundPackage == other.scene.externalForegroundPackage &&
            scene.windowId == other.scene.windowId
}

enum class ExpectedOutcomeType {
    FOREGROUND_PACKAGE, SCREEN_CHANGED, SCREEN_TYPE_CHANGED, ELEMENT_APPEARED,
    ELEMENT_DISAPPEARED, TEXT_PRESENT, INPUT_CONTAINS, NAVIGATION_OCCURRED,
    MODAL_APPEARED, RESULT_SET_CHANGED, SCROLL_CHANGED, TARGET_STATE_CHANGED, INFORMATION_RETURNED
}

data class ExpectedOutcome(
    val type: ExpectedOutcomeType,
    val summary: String,
    val packageName: String? = null,
    val text: String? = null,
    val role: SemanticRole? = null
)

data class GeneralPlanStep(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val category: ActionCategory,
    val capability: ToolCapability,
    val targetDescription: String? = null,
    val textPayload: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val strategy: String = "primary",
    val expectedOutcome: ExpectedOutcome,
    val requiresFreshPerception: Boolean = true,
    val requiresVerification: Boolean = true,
    val risk: ToolRisk = ToolRisk.LOW,
    val maxRetries: Int = 1
)

enum class GeneralVerificationStatus { SUCCESS, FAILURE, UNKNOWN }

data class GeneralVerificationResult(
    val status: GeneralVerificationStatus,
    val expected: String,
    val observed: String,
    val confidence: Double,
    val evidence: List<String> = emptyList(),
    val mismatchReason: String? = null
)

data class GeneralActionResult(
    val accepted: Boolean,
    val targetId: String? = null,
    val failureReason: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class GeneralRuntimeTask(
    val id: String = UUID.randomUUID().toString(),
    val turnId: Long,
    val intent: StructuredAgentIntent,
    val status: AgentRuntimeStatus = AgentRuntimeStatus.CREATED,
    val currentStep: GeneralPlanStep? = null,
    val previousSteps: List<GeneralPlanStep> = emptyList(),
    val actionHistory: List<GeneralActionHistory> = emptyList(),
    val rejectedTargets: Set<String> = emptySet(),
    val recoveryCount: Int = 0,
    val planRevision: Int = 0,
    val createdAt: Long,
    val updatedAt: Long = createdAt
)

data class GeneralActionHistory(
    val stepId: String,
    val capability: ToolCapability,
    val targetId: String?,
    val packageName: String,
    val beforeGeneration: Long,
    val afterGeneration: Long?,
    val accepted: Boolean,
    val verification: GeneralVerificationStatus?,
    val recoveryAttempt: Int,
    val failureReason: String?,
    val timestamp: Long,
    val targetFingerprint: String? = null
)

sealed interface PlannerResult {
    data class Next(val step: GeneralPlanStep) : PlannerResult
    data class NeedObservation(val visual: Boolean) : PlannerResult
    data class VerifyPrevious(val step: GeneralPlanStep) : PlannerResult
    data class Recover(val step: GeneralPlanStep) : PlannerResult
    data class NeedClarification(val message: String) : PlannerResult
    data class Complete(val reason: String) : PlannerResult
    data class Fail(val reason: String) : PlannerResult
}

/** Plans one safe next step. Existing platform executors remain adapters beneath capabilities. */
class GeneralAgentPlanner {
    fun next(task: GeneralRuntimeTask, perception: PerceptionSnapshot?, relevantTools: List<ToolDefinition>): PlannerResult {
        if (!task.intent.requiresAction) return PlannerResult.Complete("conversation_tools_locked")
        if (task.recoveryCount > MAX_RECOVERIES) return PlannerResult.Fail("safe_retry_limit")
        task.currentStep?.takeIf { task.status == AgentRuntimeStatus.WAITING_FOR_RESULT }?.let {
            return PlannerResult.VerifyPrevious(it)
        }
        if (perception == null && task.intent.requiredCapabilities.any { it.requiresScreen() }) {
            return PlannerResult.NeedObservation(visual = task.intent.turnIntent == TurnIntent.SCREEN_QUESTION)
        }
        if (perception?.scene?.modal != null && perception.scene.modal != ModalKind.NONE &&
            ModalSafetyPolicy.requiresAuthorization(perception.scene)
        ) {
            return PlannerResult.NeedClarification("Screen par protected dialog hai. Kya karun?")
        }
        val capability = primaryCapability(task.intent.requiredCapabilities)
            ?.takeIf { wanted -> relevantTools.any { it.capability == wanted } }
            ?: return PlannerResult.Fail("no_safe_tool")
        val expected = expectedFor(capability, task.intent)
        return PlannerResult.Next(
            GeneralPlanStep(
                taskId = task.id,
                category = categoryFor(capability),
                capability = capability,
                targetDescription = task.intent.targetDescription,
                textPayload = task.intent.textHint,
                parameters = task.intent.parameters,
                strategy = if (task.status == AgentRuntimeStatus.RECOVERING) "safe_retry_${task.recoveryCount}" else "primary",
                expectedOutcome = expected,
                requiresFreshPerception = capability.requiresScreen(),
                requiresVerification = capability !in setOf(ToolCapability.OBSERVE_SCREEN, ToolCapability.VISUAL_CHECK),
                risk = relevantTools.first { it.capability == capability }.risk
            )
        ).let { if (task.status == AgentRuntimeStatus.RECOVERING) PlannerResult.Recover(it.step) else it }
    }

    private fun primaryCapability(capabilities: Set<ToolCapability>): ToolCapability? {
        val executionOrder = listOf(
            ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH,
            ToolCapability.ACCESSIBILITY_SCROLL, ToolCapability.ACCESSIBILITY_CLICK,
            ToolCapability.ACCESSIBILITY_TYPE, ToolCapability.BACK, ToolCapability.HOME,
            ToolCapability.OPEN_APP, ToolCapability.SWITCH_APP,
            ToolCapability.OBSERVE_SCREEN, ToolCapability.VISUAL_CHECK, ToolCapability.VERIFY_SCREEN
        )
        return executionOrder.firstOrNull { it in capabilities }
            ?: capabilities.firstOrNull()
    }

    private fun expectedFor(capability: ToolCapability, intent: StructuredAgentIntent) = when (capability) {
        ToolCapability.OPEN_APP, ToolCapability.SWITCH_APP -> ExpectedOutcome(ExpectedOutcomeType.FOREGROUND_PACKAGE, "requested app foreground", intent.relevantApp)
        ToolCapability.ACCESSIBILITY_SCROLL, ToolCapability.SWIPE -> ExpectedOutcome(ExpectedOutcomeType.SCROLL_CHANGED, "visible content changes")
        ToolCapability.ACCESSIBILITY_TYPE -> ExpectedOutcome(ExpectedOutcomeType.INPUT_CONTAINS, "owned input contains payload", text = intent.textHint)
        ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH -> ExpectedOutcome(
            ExpectedOutcomeType.RESULT_SET_CHANGED, "search results visible", intent.relevantApp, intent.textHint
        )
        ToolCapability.ACCESSIBILITY_CLICK, ToolCapability.GESTURE -> ExpectedOutcome(ExpectedOutcomeType.TARGET_STATE_CHANGED, intent.desiredEndState ?: "target state or navigation changes", role = intent.roleHint)
        ToolCapability.BACK, ToolCapability.HOME -> ExpectedOutcome(ExpectedOutcomeType.NAVIGATION_OCCURRED, "navigation changes current screen")
        else -> ExpectedOutcome(ExpectedOutcomeType.SCREEN_CHANGED, intent.desiredEndState ?: "screen reflects requested result")
    }

    private fun categoryFor(capability: ToolCapability) = when (capability) {
        ToolCapability.BROWSER_SEARCH -> ActionCategory.BROWSER
        ToolCapability.WEB_SEARCH -> ActionCategory.WEB_SEARCH
        ToolCapability.OBSERVE_SCREEN, ToolCapability.VISUAL_CHECK, ToolCapability.ACCESSIBILITY_SCREENSHOT -> ActionCategory.SCREEN_PERCEPTION
        ToolCapability.MEMORY_READ -> ActionCategory.MEMORY
        ToolCapability.API_QUERY -> ActionCategory.APIS
        ToolCapability.CONTINUOUS_SCREEN -> ActionCategory.MEDIA
        else -> ActionCategory.PHONE_CONTROL
    }

    private fun ToolCapability.requiresScreen() = this in setOf(
        ToolCapability.OBSERVE_SCREEN, ToolCapability.VISUAL_CHECK, ToolCapability.FIND_ELEMENT,
        ToolCapability.ACCESSIBILITY_CLICK, ToolCapability.LONG_PRESS, ToolCapability.ACCESSIBILITY_SCROLL,
        ToolCapability.SWIPE, ToolCapability.ACCESSIBILITY_TYPE, ToolCapability.CLEAR_TEXT,
        ToolCapability.PRESS_ENTER, ToolCapability.ACCESSIBILITY_SCREENSHOT, ToolCapability.GESTURE,
        ToolCapability.BACK, ToolCapability.HOME, ToolCapability.WAIT_FOR_SCREEN, ToolCapability.VERIFY_SCREEN
    )

    companion object { const val MAX_RECOVERIES = 2 }
}

interface GeneralToolAdapter {
    val adapterId: String
    val definition: ToolDefinition
    val observationDelayMs: Long get() = 400L
    fun execute(step: GeneralPlanStep, perception: PerceptionSnapshot): GeneralActionResult
}

class ProductionGeneralToolAdapter(
    override val adapterId: String,
    override val definition: ToolDefinition,
    override val observationDelayMs: Long = 400L,
    private val executor: (GeneralPlanStep, PerceptionSnapshot) -> GeneralActionResult
) : GeneralToolAdapter {
    override fun execute(step: GeneralPlanStep, perception: PerceptionSnapshot) = executor(step, perception)
}

data class ProductionAdapterExecutors(
    val scroll: (GeneralPlanStep, PerceptionSnapshot) -> GeneralActionResult,
    val browserSearch: (GeneralPlanStep, PerceptionSnapshot) -> GeneralActionResult,
    val observeScreen: (GeneralPlanStep, PerceptionSnapshot) -> GeneralActionResult,
    val verifyScreen: (GeneralPlanStep, PerceptionSnapshot) -> GeneralActionResult,
    val back: (GeneralPlanStep, PerceptionSnapshot) -> GeneralActionResult,
    val findElement: (GeneralPlanStep, PerceptionSnapshot) -> GeneralActionResult = { _, _ -> GeneralActionResult(false, failureReason = "find_not_configured") },
    val tapElement: (GeneralPlanStep, PerceptionSnapshot) -> GeneralActionResult = { _, _ -> GeneralActionResult(false, failureReason = "tap_not_configured") }
)

object ProductionGeneralAdapters {
    fun create(registry: AgentToolRegistry, executors: ProductionAdapterExecutors): List<GeneralToolAdapter> = listOf(
        ProductionGeneralToolAdapter("GenericScrollAdapter", requireNotNull(registry.forCapability(ToolCapability.ACCESSIBILITY_SCROLL)), 650L, executors.scroll),
        ProductionGeneralToolAdapter("BrowserSearchAdapter", requireNotNull(registry.forCapability(ToolCapability.BROWSER_SEARCH)), 900L, executors.browserSearch),
        ProductionGeneralToolAdapter("ObserveScreenAdapter", requireNotNull(registry.forCapability(ToolCapability.OBSERVE_SCREEN)), 0L, executors.observeScreen),
        ProductionGeneralToolAdapter("VerifyScreenAdapter", requireNotNull(registry.forCapability(ToolCapability.VERIFY_SCREEN)), 0L, executors.verifyScreen),
        ProductionGeneralToolAdapter("BackAdapter", requireNotNull(registry.forCapability(ToolCapability.BACK)), 400L, executors.back),
        ProductionGeneralToolAdapter("FindElementAdapter", requireNotNull(registry.forCapability(ToolCapability.FIND_ELEMENT)), 0L, executors.findElement),
        ProductionGeneralToolAdapter("GenericSemanticTapAdapter", requireNotNull(registry.forCapability(ToolCapability.ACCESSIBILITY_CLICK)), 450L, executors.tapElement)
    )
}

class GeneralActionRouter(adapters: List<GeneralToolAdapter>) {
    private val adaptersByCapability = adapters.groupBy { it.definition.capability }

    fun select(step: GeneralPlanStep, perception: PerceptionSnapshot): GeneralToolAdapter? =
        adaptersByCapability[step.capability].orEmpty()
            .filter { !it.definition.foregroundRequired || perception.scene.externalForegroundPackage.isNotBlank() }
            .minByOrNull { it.definition.risk.ordinal }

    fun registeredCapabilities(): Set<ToolCapability> = adaptersByCapability.keys
}

data class ResolvedActionTarget(
    val elementId: String,
    val packageName: String,
    val windowId: Int,
    val generation: Long,
    val bounds: List<Int>
)

object GeneralActionSafety {
    fun validate(target: ResolvedActionTarget, perception: PerceptionSnapshot): Boolean {
        val scene = perception.scene
        if (target.packageName != scene.externalForegroundPackage || target.windowId != scene.windowId || target.generation != scene.generation) return false
        return scene.semanticElements.any { element ->
            element.id == target.elementId && element.actionable &&
                target.bounds == listOf(element.left, element.top, element.right, element.bottom)
        }
    }
}

class GeneralVerifier {
    fun verify(step: GeneralPlanStep, before: PerceptionSnapshot, after: PerceptionSnapshot): GeneralVerificationResult {
        val expected = step.expectedOutcome
        val packageChanged = before.scene.externalForegroundPackage != after.scene.externalForegroundPackage
        val windowChanged = before.scene.windowId != after.scene.windowId
        val generationChanged = before.scene.generation != after.scene.generation
        val elementsChanged = before.scene.semanticElements != after.scene.semanticElements
        val beforeFingerprints = before.scene.semanticElements.map(SemanticTargetFingerprint::of).toSet()
        val afterFingerprints = after.scene.semanticElements.map(SemanticTargetFingerprint::of).toSet()
        val shared = beforeFingerprints.intersect(afterFingerprints).size
        val union = beforeFingerprints.union(afterFingerprints).size.coerceAtLeast(1)
        val majorSemanticChange = shared.toDouble() / union < .55
        val fresh = after.capturedAt > before.capturedAt || after.scene.observedAt > before.scene.observedAt
        if (!fresh) return GeneralVerificationResult(
            GeneralVerificationStatus.UNKNOWN, expected.summary, "observation was not fresh", .2,
            mismatchReason = "stale_observation"
        )
        val scrollEvidence = if (expected.type == ExpectedOutcomeType.SCROLL_CHANGED) {
            ScrollMovementAnalyzer.analyze(before.scene, after.scene)
        } else null
        val success = when (expected.type) {
            ExpectedOutcomeType.FOREGROUND_PACKAGE -> expected.packageName != null && after.scene.externalForegroundPackage == expected.packageName
            ExpectedOutcomeType.TEXT_PRESENT -> expected.text?.let { needle -> after.scene.semanticElements.any { it.label.contains(needle, true) } } == true
            ExpectedOutcomeType.INPUT_CONTAINS -> expected.text?.let { needle -> after.scene.semanticElements.any { it.role == SemanticRole.TEXT_INPUT && it.label.contains(needle, true) } } == true
            ExpectedOutcomeType.MODAL_APPEARED -> after.scene.modal != ModalKind.NONE
            ExpectedOutcomeType.TARGET_STATE_CHANGED -> {
                val expectedRolePresent = expected.role?.let { role -> after.scene.semanticElements.any { it.role == role } } ?: true
                (packageChanged || windowChanged || generationChanged || majorSemanticChange) && expectedRolePresent
            }
            ExpectedOutcomeType.RESULT_SET_CHANGED -> {
                val packageMatches = expected.packageName == null || after.scene.externalForegroundPackage == expected.packageName
                val tokens = expected.text.orEmpty().lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
                    .filter { it.length >= 2 }
                packageMatches && tokens.isNotEmpty() && after.scene.semanticElements.any { element ->
                    tokens.any { element.label.lowercase().contains(it) }
                }
            }
            ExpectedOutcomeType.NAVIGATION_OCCURRED -> packageChanged || windowChanged || generationChanged || majorSemanticChange
            ExpectedOutcomeType.SCREEN_CHANGED,
            ExpectedOutcomeType.SCREEN_TYPE_CHANGED -> packageChanged || generationChanged || elementsChanged
            ExpectedOutcomeType.SCROLL_CHANGED -> scrollEvidence?.proven == true
            ExpectedOutcomeType.ELEMENT_APPEARED -> expected.role?.let { role -> after.scene.semanticElements.any { it.role == role } } == true
            ExpectedOutcomeType.ELEMENT_DISAPPEARED -> expected.role?.let { role -> after.scene.semanticElements.none { it.role == role } } == true
            ExpectedOutcomeType.INFORMATION_RETURNED -> false
        }
        if (success) return GeneralVerificationResult(
            GeneralVerificationStatus.SUCCESS, expected.summary,
            if (scrollEvidence != null) "viewport movement proven" else "fresh screen matched", .9,
            listOf("fresh_observation") + (scrollEvidence?.evidenceTokens ?: emptyList())
        )
        val unchanged = !packageChanged && !generationChanged && !elementsChanged
        val wrongPackage = expected.packageName != null && after.scene.externalForegroundPackage != expected.packageName
        return GeneralVerificationResult(
            if (wrongPackage || expected.type == ExpectedOutcomeType.TARGET_STATE_CHANGED) GeneralVerificationStatus.FAILURE
            else GeneralVerificationStatus.UNKNOWN,
            expected.summary,
            if (unchanged) "no observable change" else "screen changed differently",
            if (unchanged) .45 else .65,
            listOf("fresh_observation") + (scrollEvidence?.evidenceTokens ?: emptyList()),
            if (wrongPackage) "wrong_foreground_package" else if (unchanged) "insufficient_evidence" else "expected_state_missing"
        )
    }
}

data class ScrollMovementEvidence(
    val stableAnchorCount: Int,
    val movedAnchorCount: Int,
    val medianDeltaY: Int,
    val newVisibleElements: Int,
    val lostVisibleElements: Int,
    val consistentDirection: Boolean,
    val contentWindowShift: Boolean,
    val proven: Boolean
) {
    val evidenceTokens: List<String> get() = listOf(
        "stable_anchors=$stableAnchorCount", "moved_anchors=$movedAnchorCount",
        "median_delta_y=$medianDeltaY", "new_visible=$newVisibleElements",
        "lost_visible=$lostVisibleElements", "consistent_direction=$consistentDirection",
        "content_window_shift=$contentWindowShift"
    )
}

/** Proves viewport displacement from stable semantic content. Snapshot freshness, generation,
 * and node-count churn are intentionally excluded: none of them proves a scroll occurred. */
object ScrollMovementAnalyzer {
    private const val MIN_ANCHOR_DELTA_PX = 40

    fun analyze(before: ScreenScene, after: ScreenScene): ScrollMovementEvidence {
        fun key(element: SemanticElement) = "${element.role}:${element.label.trim().lowercase()}"
        fun uniqueByKey(elements: List<SemanticElement>) = elements
            .filter { it.label.isNotBlank() && it.role != SemanticRole.SCROLL_CONTAINER }
            .groupBy(::key).filterValues { it.size == 1 }.mapValues { it.value.single() }
        val pre = uniqueByKey(before.semanticElements)
        val post = uniqueByKey(after.semanticElements)
        val common = pre.keys intersect post.keys
        val deltas = common.map { post.getValue(it).centerY - pre.getValue(it).centerY }
        val meaningful = deltas.filter { kotlin.math.abs(it) >= MIN_ANCHOR_DELTA_PX }
        val median = meaningful.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        val sameDirection = meaningful.isNotEmpty() &&
            maxOf(meaningful.count { it > 0 }, meaningful.count { it < 0 }) * 4 >= meaningful.size * 3
        val newCount = (post.keys - pre.keys).size
        val lostCount = (pre.keys - post.keys).size
        val windowShift = newCount >= 2 && lostCount >= 2 && common.isNotEmpty() && sameDirection
        val anchorMovement = meaningful.size >= 2 && sameDirection
        return ScrollMovementEvidence(
            common.size, meaningful.size, median, newCount, lostCount, sameDirection,
            windowShift, anchorMovement || windowShift
        )
    }
}

/** UNKNOWN scroll evidence is sampled again without routing another physical action. */
object ScrollVerificationResamplePolicy {
    const val MAX_RESAMPLES = 2
    const val DELAY_MS = 250L

    fun shouldResample(dispatchAccepted: Boolean, movementProven: Boolean, resampleCount: Int): Boolean =
        dispatchAccepted && !movementProven && resampleCount < MAX_RESAMPLES
}

sealed interface RecoveryDecision {
    data class Retry(val rejectedTarget: String?) : RecoveryDecision
    data class Clarify(val message: String) : RecoveryDecision
    data class Fail(val reason: String) : RecoveryDecision
    data object PauseForModal : RecoveryDecision
}

class GeneralRecoveryEngine(private val maxRetries: Int = 1) {
    fun decide(task: GeneralRuntimeTask, verification: GeneralVerificationResult, scene: ScreenScene, targetId: String?): RecoveryDecision {
        if (scene.modal != ModalKind.NONE) return RecoveryDecision.PauseForModal
        if (verification.status == GeneralVerificationStatus.SUCCESS) return RecoveryDecision.Fail("recovery_not_needed")
        if (task.actionHistory.lastOrNull()?.failureReason in setOf(
                "target_ambiguous", "target_not_found", "protected_modal", "clarification_required"
            )
        ) return RecoveryDecision.Clarify("Kaunsa wala?")
        // UNKNOWN can mean the gesture worked but the available scene lacked stable anchors.
        // Repeating it would risk two visible scrolls for one user turn.
        if (verification.status == GeneralVerificationStatus.UNKNOWN) {
            return RecoveryDecision.Clarify("Screen movement reliably verify nahi hua.")
        }
        if (task.recoveryCount >= maxRetries) return RecoveryDecision.Clarify("Target verify nahi hua. Kaunsa wala?")
        return RecoveryDecision.Retry(task.actionHistory.lastOrNull()?.targetFingerprint ?: targetId)
    }
}

object ModalSafetyPolicy {
    private val protectedTerms = Regex("\\b(?:allow|permission|pay|purchase|delete|remove|send|post|password|pin|account|security|confirm)\\b", RegexOption.IGNORE_CASE)
    fun requiresAuthorization(scene: ScreenScene): Boolean = when (scene.modal) {
        ModalKind.NONE, ModalKind.KEYBOARD, ModalKind.MENU -> false
        ModalKind.PERMISSION, ModalKind.SYSTEM_DIALOG, ModalKind.UNKNOWN -> true
        ModalKind.APP_DIALOG -> scene.semanticElements.any { protectedTerms.containsMatchIn(it.label) }
    }
}

/** Single state owner for plan/action/observation/verification/recovery. */
class GeneralAgentRuntime(
    private val registry: AgentToolRegistry = AgentToolRegistry(),
    private val planner: GeneralAgentPlanner = GeneralAgentPlanner(),
    private val verifier: GeneralVerifier = GeneralVerifier(),
    private val recovery: GeneralRecoveryEngine = GeneralRecoveryEngine(),
    private val now: () -> Long = System::currentTimeMillis
) {
    @Volatile private var active: GeneralRuntimeTask? = null
    @Volatile private var lastCompleted: GeneralRuntimeTask? = null
    private val beforeByStep = mutableMapOf<String, PerceptionSnapshot>()

    @Synchronized fun start(turnId: Long, intent: StructuredAgentIntent, taskId: String? = null): GeneralRuntimeTask? {
        active = active?.copy(status = AgentRuntimeStatus.CANCELLED, updatedAt = now())
        beforeByStep.clear()
        if (!intent.requiresAction) { active = null; return null }
        return GeneralRuntimeTask(id = taskId ?: UUID.randomUUID().toString(), turnId = turnId, intent = intent,
            status = AgentRuntimeStatus.OBSERVING, createdAt = now()).also { active = it }
    }

    fun activeTask(): GeneralRuntimeTask? = active
    fun lastCompletedTask(): GeneralRuntimeTask? = lastCompleted

    @Synchronized fun next(perception: PerceptionSnapshot?): PlannerResult {
        val task = active ?: return PlannerResult.Fail("no_active_task")
        val tools = registry.relevant(task.intent.requiredCapabilities)
        val result = planner.next(task, perception, tools)
        active = when (result) {
            is PlannerResult.Next -> task.copy(status = AgentRuntimeStatus.READY_TO_ACT, currentStep = result.step, planRevision = task.planRevision + 1, updatedAt = now())
            is PlannerResult.Recover -> task.copy(status = AgentRuntimeStatus.READY_TO_ACT, currentStep = result.step, planRevision = task.planRevision + 1, updatedAt = now())
            is PlannerResult.VerifyPrevious -> task.copy(status = AgentRuntimeStatus.VERIFYING, updatedAt = now())
            is PlannerResult.NeedClarification -> task.copy(status = AgentRuntimeStatus.NEEDS_CLARIFICATION, updatedAt = now())
            is PlannerResult.Complete -> task.copy(status = AgentRuntimeStatus.COMPLETED, updatedAt = now()).also { lastCompleted = it }
            is PlannerResult.Fail -> task.copy(status = AgentRuntimeStatus.FAILED, updatedAt = now()).also { lastCompleted = it }
            else -> task.copy(status = AgentRuntimeStatus.OBSERVING, updatedAt = now())
        }
        return result
    }

    @Synchronized fun enrich(parameters: Map<String, String>, relevantApp: String? = null, textHint: String? = null) {
        active = active?.let { task ->
            task.copy(intent = task.intent.copy(
                parameters = task.intent.parameters + parameters,
                relevantApp = relevantApp ?: task.intent.relevantApp,
                textHint = textHint ?: task.intent.textHint
            ), updatedAt = now())
        }
    }

    @Synchronized fun recordAction(step: GeneralPlanStep, result: GeneralActionResult, before: PerceptionSnapshot): GeneralRuntimeTask {
        val task = requireNotNull(active) { "no active task" }
        require(step.taskId == task.id) { "step belongs to another task" }
        val history = GeneralActionHistory(step.id, step.capability, result.targetId, before.scene.externalForegroundPackage,
            before.scene.generation, null, result.accepted, null, task.recoveryCount, result.failureReason, now(),
            result.metadata["fingerprint"])
        beforeByStep[step.id] = before
        return task.copy(status = if (result.accepted) AgentRuntimeStatus.WAITING_FOR_RESULT else AgentRuntimeStatus.RECOVERING,
            previousSteps = task.previousSteps + step, actionHistory = task.actionHistory + history, updatedAt = now()).also { active = it }
    }

    @Synchronized fun verify(after: PerceptionSnapshot): Pair<GeneralVerificationResult, RecoveryDecision?> {
        val task = requireNotNull(active) { "no active task" }
        val step = requireNotNull(task.currentStep) { "no active step" }
        val history = task.actionHistory.lastOrNull { it.stepId == step.id }
        val before = beforeByStep.remove(step.id) ?: after
        val actionAccepted = history?.accepted != false
        val result = if (actionAccepted) verifier.verify(step, before, after) else GeneralVerificationResult(
            GeneralVerificationStatus.FAILURE, step.expectedOutcome.summary,
            history?.failureReason ?: "adapter rejected action", .95,
            evidence = listOf("adapter_rejected"), mismatchReason = history?.failureReason ?: "dispatch_rejected"
        )
        val updatedHistory = task.actionHistory.map { if (it.stepId == step.id) it.copy(afterGeneration = after.scene.generation, verification = result.status) else it }
        if (result.status == GeneralVerificationStatus.SUCCESS) {
            val completed = task.copy(status = AgentRuntimeStatus.COMPLETED, actionHistory = updatedHistory, updatedAt = now())
            active = null; lastCompleted = completed
            return result to null
        }
        val decision = recovery.decide(task, result, after.scene, history?.targetId)
        active = when (decision) {
            is RecoveryDecision.Retry -> task.copy(status = AgentRuntimeStatus.RECOVERING, recoveryCount = task.recoveryCount + 1,
                rejectedTargets = decision.rejectedTarget?.let { task.rejectedTargets + it } ?: task.rejectedTargets,
                actionHistory = updatedHistory, currentStep = null, updatedAt = now())
            is RecoveryDecision.PauseForModal -> task.copy(status = AgentRuntimeStatus.OBSERVING, actionHistory = updatedHistory, updatedAt = now())
            is RecoveryDecision.Clarify -> task.copy(status = AgentRuntimeStatus.NEEDS_CLARIFICATION, actionHistory = updatedHistory, updatedAt = now())
            is RecoveryDecision.Fail -> task.copy(status = AgentRuntimeStatus.FAILED, actionHistory = updatedHistory, updatedAt = now())
        }
        return result to decision
    }

    /** Allows a validated platform adapter to feed its stronger domain evidence back into the
     * same general task owner. UNKNOWN remains non-success and FAILURE is never inferred. */
    @Synchronized fun completeFromAdapter(status: GeneralVerificationStatus, observed: String): GeneralRuntimeTask? {
        val task = active ?: return null
        val step = task.currentStep
        val updatedHistory = task.actionHistory.map {
            if (step != null && it.stepId == step.id) it.copy(verification = status) else it
        }
        val terminal = when (status) {
            GeneralVerificationStatus.SUCCESS -> AgentRuntimeStatus.COMPLETED
            GeneralVerificationStatus.FAILURE -> AgentRuntimeStatus.FAILED
            GeneralVerificationStatus.UNKNOWN -> AgentRuntimeStatus.NEEDS_CLARIFICATION
        }
        val completed = task.copy(
            status = terminal,
            actionHistory = updatedHistory,
            updatedAt = now(),
            intent = task.intent.copy(desiredEndState = observed)
        )
        active = null
        lastCompleted = completed
        beforeByStep.clear()
        return completed
    }

    @Synchronized fun cancel() { active = active?.copy(status = AgentRuntimeStatus.CANCELLED, updatedAt = now()); active = null; beforeByStep.clear() }
}

object GeneralAgentRuntimeStore { val runtime = GeneralAgentRuntime() }
