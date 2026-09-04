package com.myra.assistant.screen

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

enum class VisualAwarenessState { ON, OFF }

class VisualAwarenessPreferences(context: Context) {
    private val values = context.getSharedPreferences("myra", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = values.getBoolean(KEY, false)
        set(value) = values.edit().putBoolean(KEY, value).apply()
    var proactiveAssistanceEnabled: Boolean
        get() = values.getBoolean(KEY_PROACTIVE, false)
        set(value) = values.edit().putBoolean(KEY_PROACTIVE, value).apply()

    companion object {
        private const val KEY = "visual_awareness_enabled"
        private const val KEY_PROACTIVE = "proactive_visual_assistance_enabled"
    }
}

object VisualObservationPolicy {
    fun mayRequestScreenshot(eyeEnabled: Boolean, sdkInt: Int): Boolean = eyeEnabled && sdkInt >= 30
    fun requiresMediaProjection(normalVisualAction: Boolean): Boolean = false
}

data class AccessibilityScreenshot(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val capturedAt: Long,
    val packageName: String,
    val windowId: Int,
    val generation: Long
)

enum class VisualFrameSource { ACCESSIBILITY_CACHE, ACCESSIBILITY_FRESH }

data class VisualScreenshotSelection(
    val screenshot: AccessibilityScreenshot,
    val source: VisualFrameSource
)

/** Allows exactly one terminal result for a screenshot request. A late Android
 * callback may warm the cache, but it cannot answer a timed-out/replaced turn. */
class VisualCaptureCompletionGate {
    private val completed = AtomicBoolean(false)
    fun tryComplete(): Boolean = completed.compareAndSet(false, true)
}

object VisualScreenshotTimeoutPolicy {
    const val TIMEOUT_MS = 1_200L
    const val OUTER_ACQUISITION_TIMEOUT_MS = 1_200L
    const val SAFE_FALLBACK_MAX_AGE_MS = 2_500L
}

/** One acquisition owner from visualFrameRequested through frame/fallback/failure. */
class VisualAcquisitionGate(
    val visualTurnId: String,
    val requestedAt: Long,
    val deadlineAt: Long = requestedAt + VisualScreenshotTimeoutPolicy.OUTER_ACQUISITION_TIMEOUT_MS
) {
    private val completed = AtomicBoolean(false)

    fun mayDispatch(currentVisualTurnId: String?, now: Long): Boolean =
        !completed.get() && currentVisualTurnId == visualTurnId && now <= deadlineAt

    fun tryComplete(currentVisualTurnId: String?, now: Long): Boolean =
        currentVisualTurnId == visualTurnId && now <= deadlineAt && completed.compareAndSet(false, true)

    fun tryTimeout(now: Long): Boolean = now >= deadlineAt && completed.compareAndSet(false, true)
    fun isComplete(): Boolean = completed.get()
}

object SemanticScreenFallbackPolicy {
    const val MAX_AGE_MS = 2_500L
    fun mayAnswer(
        expectedPackage: String,
        expectedWindowId: Int,
        expectedGeneration: Long,
        actualPackage: String?,
        actualWindowId: Int?,
        actualGeneration: Long?,
        semanticElementCount: Int,
        ageMs: Long
    ): Boolean = actualPackage == expectedPackage && actualWindowId == expectedWindowId &&
        actualGeneration == expectedGeneration && semanticElementCount > 0 && ageMs in 0..MAX_AGE_MS
}

/** In-memory only. Raw screenshots never enter Room or long-term memory. */
object AccessibilityVisualCache {
    private data class Entry(
        val screenshot: AccessibilityScreenshot,
        val semanticSignature: String
    )

    @Volatile private var entry: Entry? = null

    @Synchronized fun put(screenshot: AccessibilityScreenshot, semanticSignature: String) {
        entry = Entry(screenshot, semanticSignature)
    }

    fun fresh(
        packageName: String,
        windowId: Int,
        generation: Long,
        semanticSignature: String,
        now: Long,
        maxAgeMs: Long
    ): AccessibilityScreenshot? {
        val current = entry ?: return null
        val frame = current.screenshot
        return frame.takeIf {
            it.packageName == packageName && it.windowId == windowId &&
                it.generation == generation && current.semanticSignature == semanticSignature &&
                (now - it.capturedAt).coerceAtLeast(0L) <= maxAgeMs
        }
    }

    fun selectFresh(
        packageName: String,
        windowId: Int,
        generation: Long,
        semanticSignature: String,
        now: Long,
        maxAgeMs: Long
    ): VisualScreenshotSelection? = fresh(
        packageName, windowId, generation, semanticSignature, now, maxAgeMs
    )?.let { VisualScreenshotSelection(it, VisualFrameSource.ACCESSIBILITY_CACHE) }

    @Synchronized fun invalidate() { entry = null }
}
