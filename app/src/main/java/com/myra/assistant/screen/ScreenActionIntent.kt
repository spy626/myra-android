package com.myra.assistant.screen

import java.util.Locale
import java.util.UUID

data class ScreenActionIntent(
    val actionId: String,
    val turnId: Long,
    val screenSessionId: String,
    val requestedText: String,
    val normalizedTarget: String?,
    val position: String?,
    val ordinal: Int?,
    val appPackage: String?,
    val targetResolutionTimestamp: Long,
    val sourceFrameId: Long,
    val confidence: Double,
    val resolverVersion: String = ScreenActionIntentRegistry.RESOLVER_VERSION,
    val activeWindowId: Int? = null,
    val screenContextGeneration: Long = 0L
)

/** One current screen action. Creating a newer action invalidates every older callback. */
class ScreenActionIntentRegistry {
    @Volatile private var current: ScreenActionIntent? = null

    @Synchronized fun create(
        turnId: Long,
        screenSessionId: String,
        requestedText: String,
        target: String?,
        position: String?,
        ordinal: Int?,
        appPackage: String?,
        resolvedAt: Long,
        sourceFrameId: Long,
        confidence: Double,
        activeWindowId: Int? = null,
        screenContextGeneration: Long = 0L
    ): ScreenActionIntent {
        return ScreenActionIntent(
            actionId = UUID.randomUUID().toString(),
            turnId = turnId,
            screenSessionId = screenSessionId,
            requestedText = requestedText.trim(),
            normalizedTarget = normalize(target),
            position = position?.lowercase(Locale.ROOT),
            ordinal = ordinal,
            appPackage = appPackage,
            targetResolutionTimestamp = resolvedAt,
            sourceFrameId = sourceFrameId,
            confidence = confidence.coerceIn(0.0, 1.0),
            activeWindowId = activeWindowId,
            screenContextGeneration = screenContextGeneration
        ).also { current = it }
    }

    fun snapshot(): ScreenActionIntent? = current

    fun isCurrent(actionId: String, turnId: Long, screenSessionId: String): Boolean {
        val value = current ?: return false
        return value.actionId == actionId && value.turnId == turnId &&
            value.screenSessionId == screenSessionId
    }

    fun isExecutable(
        actionId: String,
        turnId: Long,
        screenSessionId: String,
        foreground: ForegroundAppContext?
    ): Boolean {
        val value = current ?: return false
        if (!isCurrent(actionId, turnId, screenSessionId) || foreground == null) return false
        return value.appPackage == foreground.packageName &&
            value.activeWindowId == foreground.windowId &&
            value.screenContextGeneration == foreground.generation
    }

    @Synchronized fun cancel(actionId: String? = null): ScreenActionIntent? {
        val value = current ?: return null
        if (actionId != null && value.actionId != actionId) return null
        current = null
        return value
    }

    companion object {
        const val RESOLVER_VERSION = "screen-target-v2"
        private fun normalize(value: String?): String? = value?.lowercase(Locale.ROOT)
            ?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf(String::isNotBlank)
    }
}
