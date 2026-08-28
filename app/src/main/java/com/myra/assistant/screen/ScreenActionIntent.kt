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
    val resolverVersion: String = ScreenActionIntentRegistry.RESOLVER_VERSION
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
        confidence: Double
    ): ScreenActionIntent {
        return ScreenActionIntent(
            UUID.randomUUID().toString(), turnId, screenSessionId, requestedText.trim(),
            normalize(target), position?.lowercase(Locale.ROOT), ordinal, appPackage,
            resolvedAt, sourceFrameId, confidence.coerceIn(0.0, 1.0)
        ).also { current = it }
    }

    fun snapshot(): ScreenActionIntent? = current

    fun isCurrent(actionId: String, turnId: Long, screenSessionId: String): Boolean {
        val value = current ?: return false
        return value.actionId == actionId && value.turnId == turnId &&
            value.screenSessionId == screenSessionId
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
