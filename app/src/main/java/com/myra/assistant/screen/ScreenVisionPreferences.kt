package com.myra.assistant.screen

import android.content.Context

class ScreenVisionPreferences(context: Context) {
    private val values = context.getSharedPreferences("myra", Context.MODE_PRIVATE)

    var visionEnabled: Boolean
        get() = values.getBoolean(KEY_VISION, false)
        set(value) = values.edit().putBoolean(KEY_VISION, value).apply()
    var automaticLearning: Boolean
        get() = values.getBoolean(KEY_AUTO_LEARNING, false)
        set(value) = values.edit().putBoolean(KEY_AUTO_LEARNING, value).apply()
    var saveScreenMemories: Boolean
        get() = values.getBoolean(KEY_SAVE_MEMORIES, false)
        set(value) = values.edit().putBoolean(KEY_SAVE_MEMORIES, value).apply()
    var sensitiveContentProtection: Boolean
        get() = values.getBoolean(KEY_SENSITIVE_PROTECTION, true)
        set(value) = values.edit().putBoolean(KEY_SENSITIVE_PROTECTION, value).apply()
    var analysisIntervalMs: Long
        get() = values.getLong(KEY_INTERVAL, 3_000L).coerceIn(1_500L, 15_000L)
        set(value) = values.edit().putLong(KEY_INTERVAL, value.coerceIn(1_500L, 15_000L)).apply()

    companion object {
        private const val KEY_VISION = "screen_vision_enabled"
        private const val KEY_AUTO_LEARNING = "screen_auto_learning"
        private const val KEY_SAVE_MEMORIES = "screen_save_memories"
        private const val KEY_SENSITIVE_PROTECTION = "screen_sensitive_protection"
        private const val KEY_INTERVAL = "screen_analysis_interval_ms"
    }
}

