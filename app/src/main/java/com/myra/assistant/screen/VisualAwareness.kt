package com.myra.assistant.screen

import android.content.Context

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
