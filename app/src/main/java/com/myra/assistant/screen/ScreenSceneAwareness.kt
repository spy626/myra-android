package com.myra.assistant.screen

import com.myra.assistant.agent.CurrentActivityContext
import com.myra.assistant.agent.SemanticElement
import java.util.Locale

/** Short-lived perception evidence only. No snapshot or frame is persisted. */
data class SceneElementSnapshot(
    val elementId: String,
    val text: String,
    val role: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val focused: Boolean = false
) {
    val horizontalPosition: ScenePosition
        get() = when {
            centerX < 360 -> ScenePosition.LEFT
            centerX > 720 -> ScenePosition.RIGHT
            else -> ScenePosition.CENTER
        }
    val verticalPosition: ScenePosition
        get() = when {
            centerY < 640 -> ScenePosition.TOP
            centerY > 1280 -> ScenePosition.BOTTOM
            else -> ScenePosition.MIDDLE
        }
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun stableKey(): String = listOf(role, text.lowercase(Locale.ROOT).trim()).joinToString(":")
}

enum class ScenePosition { LEFT, CENTER, RIGHT, TOP, MIDDLE, BOTTOM }

data class ScreenSceneSnapshot(
    val timestamp: Long,
    val foregroundPackage: String,
    val windowId: Int,
    val screenGeneration: Long,
    val semanticSignature: String,
    val visibleElements: List<SceneElementSnapshot>,
    val visualFrameTimestamp: Long? = null,
    val visualFrameGeneration: Long? = null,
    val sceneRevision: Long,
    val dialogVisible: Boolean = false,
    val scrollObservedAt: Long? = null,
    val density: Float = 1f
)

enum class SceneDeltaType {
    ELEMENT_APPEARED, ELEMENT_DISAPPEARED, ELEMENT_MOVED, ELEMENT_STATE_CHANGED,
    ELEMENT_TEXT_CHANGED, SCREEN_SCROLLED, WINDOW_CHANGED, PACKAGE_CHANGED,
    DIALOG_APPEARED, DIALOG_DISAPPEARED, UNKNOWN_CHANGE
}

data class SceneDeltaEvent(
    val type: SceneDeltaType,
    val elementId: String? = null,
    val fromHorizontal: ScenePosition? = null,
    val toHorizontal: ScenePosition? = null,
    val fromVertical: ScenePosition? = null,
    val toVertical: ScenePosition? = null
)

data class SceneDelta(
    val previousRevision: Long,
    val currentRevision: Long,
    val changes: List<SceneDeltaEvent>
)

enum class ScreenEvidenceFreshness { FRESH, STALE, INSUFFICIENT }

data class ScreenEvidenceMetadata(
    val observedAt: Long,
    val sceneRevision: Long,
    val packageName: String,
    val windowId: Int,
    val generation: Long,
    val screenshotCapturedAt: Long? = null
)

object ScreenEvidenceFreshnessPolicy {
    fun evaluate(
        evidence: ScreenEvidenceMetadata?,
        current: ScreenSceneSnapshot?,
        now: Long,
        maxAgeMs: Long
    ): ScreenEvidenceFreshness {
        if (evidence == null || current == null) return ScreenEvidenceFreshness.INSUFFICIENT
        if (evidence.packageName != current.foregroundPackage || evidence.windowId != current.windowId ||
            evidence.generation != current.screenGeneration || evidence.sceneRevision < current.sceneRevision
        ) return ScreenEvidenceFreshness.STALE
        val age = now - evidence.observedAt
        return if (age in 0..maxAgeMs) ScreenEvidenceFreshness.FRESH else ScreenEvidenceFreshness.STALE
    }
}

object ScreenSceneDeltaAnalyzer {
    fun analyze(previous: ScreenSceneSnapshot?, current: ScreenSceneSnapshot): SceneDelta {
        if (previous == null) return SceneDelta(0L, current.sceneRevision, emptyList())
        val changes = mutableListOf<SceneDeltaEvent>()
        if (previous.foregroundPackage != current.foregroundPackage) changes += SceneDeltaEvent(SceneDeltaType.PACKAGE_CHANGED)
        if (previous.windowId != current.windowId) changes += SceneDeltaEvent(SceneDeltaType.WINDOW_CHANGED)
        if (!previous.dialogVisible && current.dialogVisible) changes += SceneDeltaEvent(SceneDeltaType.DIALOG_APPEARED)
        if (previous.dialogVisible && !current.dialogVisible) changes += SceneDeltaEvent(SceneDeltaType.DIALOG_DISAPPEARED)
        if ((current.scrollObservedAt ?: 0L) > (previous.scrollObservedAt ?: 0L)) {
            changes += SceneDeltaEvent(SceneDeltaType.SCREEN_SCROLLED)
        }

        val oldGroups = previous.visibleElements.groupBy(SceneElementSnapshot::stableKey)
        val newGroups = current.visibleElements.groupBy(SceneElementSnapshot::stableKey)
        (oldGroups.keys + newGroups.keys).forEach { key ->
            val old = oldGroups[key].orEmpty().sortedWith(compareBy({ it.top }, { it.left }))
            val new = newGroups[key].orEmpty().sortedWith(compareBy({ it.top }, { it.left }))
            val remaining = new.toMutableList()
            val matched = old.mapNotNull { before ->
                // Duplicate labels are paired by nearest geometry, never enumeration index.
                val after = remaining.minByOrNull {
                    kotlin.math.abs(it.centerX - before.centerX).toLong() + kotlin.math.abs(it.centerY - before.centerY)
                } ?: return@mapNotNull null
                remaining.remove(after)
                before to after
            }
            val paired = matched.size
            repeat(paired) { index ->
                val (before, after) = matched[index]
                // Four dp (at least four pixels) ignores 1–2 px accessibility jitter.
                val threshold = maxOf(4, (4 * current.density).toInt())
                if (maxOf(kotlin.math.abs(before.left - after.left), kotlin.math.abs(before.top - after.top),
                    kotlin.math.abs(before.right - after.right), kotlin.math.abs(before.bottom - after.bottom)) >= threshold
                ) changes += SceneDeltaEvent(
                    SceneDeltaType.ELEMENT_MOVED, after.elementId,
                    before.horizontalPosition, after.horizontalPosition,
                    before.verticalPosition, after.verticalPosition
                )
                if (before.enabled != after.enabled || before.selected != after.selected || before.focused != after.focused) {
                    changes += SceneDeltaEvent(SceneDeltaType.ELEMENT_STATE_CHANGED, after.elementId)
                }
            }
            old.drop(paired).forEach { changes += SceneDeltaEvent(SceneDeltaType.ELEMENT_DISAPPEARED, it.elementId) }
            remaining.forEach { changes += SceneDeltaEvent(SceneDeltaType.ELEMENT_APPEARED, it.elementId) }
        }
        return SceneDelta(previous.sceneRevision, current.sceneRevision, changes)
    }
}

object ScreenSceneAwarenessStore {
    @Volatile private var previous: ScreenSceneSnapshot? = null
    @Volatile private var current: ScreenSceneSnapshot? = null
    @Volatile private var delta: SceneDelta? = null
    @Volatile private var mutationRevision: Long = 0L
    @Volatile private var lastMutationReason: String = "initial"
    @Volatile private var dirty = false

    @Synchronized fun markMutation(reason: String): Long {
        // Content events are hints, not proof. Keep frames unavailable until observed.
        if (!dirty && !reason.startsWith("accessibility_")) mutationRevision += 1L
        dirty = true
        lastMutationReason = reason
        return mutationRevision
    }

    @Synchronized fun publish(
        context: CurrentActivityContext,
        dialogVisible: Boolean = false,
        scrollObservedAt: Long? = null,
        density: Float = 1f
    ): ScreenSceneSnapshot {
        val old = current
        val elements = context.visibleElements.map(SemanticElement::toSceneElement)
        val signature = elements.joinToString("|") {
            "${it.stableKey()}:${it.left}:${it.top}:${it.right}:${it.bottom}:${it.enabled}:${it.selected}"
        }
        val draft = ScreenSceneSnapshot(
            context.timestamp, context.packageName, context.windowId, context.generation,
            signature, elements, context.screenshotReference?.capturedAt,
            context.screenshotReference?.let { context.generation }, mutationRevision,
            dialogVisible, scrollObservedAt, density
        )
        val analysis = ScreenSceneDeltaAnalyzer.analyze(old, draft)
        val structuralChange = analysis.changes.isNotEmpty()
        if (old == null || structuralChange && mutationRevision <= old.sceneRevision) mutationRevision += 1L
        val next = draft.copy(sceneRevision = mutationRevision)
        previous = old
        current = next
        delta = ScreenSceneDeltaAnalyzer.analyze(old, next)
        dirty = false
        return next
    }

    fun current(): ScreenSceneSnapshot? = current
    fun previous(): ScreenSceneSnapshot? = previous
    fun lastDelta(): SceneDelta? = delta
    fun currentRevision(): Long = mutationRevision
    fun hasPendingMutation(): Boolean = dirty
    fun lastMutationReason(): String = lastMutationReason

    @Synchronized fun attachVisualFrame(capturedAt: Long, packageName: String, windowId: Int, generation: Long): Boolean {
        val scene = current ?: return false
        if (scene.foregroundPackage != packageName || scene.windowId != windowId || scene.screenGeneration != generation) {
            return false
        }
        current = scene.copy(visualFrameTimestamp = capturedAt, visualFrameGeneration = generation)
        return true
    }

    @Synchronized fun reset() {
        previous = null
        current = null
        delta = null
        mutationRevision = 0L
        lastMutationReason = "reset"
        dirty = false
    }
}

private fun SemanticElement.toSceneElement() = SceneElementSnapshot(
    id, label, role.name, left, top, right, bottom,
    enabled = true, selected = selected, focused = false
)

/** External controls are intentionally forbidden; Eye remains an in-app preference. */
object ExternalScreenVisionOverlayPolicy {
    const val CREATE_ACCESSIBILITY_OVERLAY: Boolean = false
}
