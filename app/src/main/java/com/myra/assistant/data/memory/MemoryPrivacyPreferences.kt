package com.myra.assistant.data.memory

import android.content.Context

/**
 * User-owned privacy controls for passive Memory Brain learning.
 * These switches can only make passive learning stricter; hard privacy/safety gates
 * remain mandatory even when both switches are enabled.
 */
class MemoryPrivacyPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var passiveAppLearningEnabled: Boolean
        get() = prefs.getBoolean(KEY_PASSIVE_APP_LEARNING, true)
        set(value) { prefs.edit().putBoolean(KEY_PASSIVE_APP_LEARNING, value).apply() }

    var passiveContentLearningEnabled: Boolean
        get() = prefs.getBoolean(KEY_PASSIVE_CONTENT_LEARNING, true)
        set(value) { prefs.edit().putBoolean(KEY_PASSIVE_CONTENT_LEARNING, value).apply() }

    companion object {
        private const val PREFS = "lyra_memory_privacy"
        private const val KEY_PASSIVE_APP_LEARNING = "passive_app_learning"
        private const val KEY_PASSIVE_CONTENT_LEARNING = "passive_content_learning"
    }
}

/** Pure policy kept independently testable without Android storage. */
object PassiveMemoryLearningPolicy {
    fun allows(
        kind: BehaviorObservationKind,
        appLearningEnabled: Boolean,
        contentLearningEnabled: Boolean
    ): Boolean = when (kind) {
        BehaviorObservationKind.APP_USAGE -> appLearningEnabled
        BehaviorObservationKind.YOUTUBE_CHANNEL,
        BehaviorObservationKind.CONTENT_TOPIC -> contentLearningEnabled
    }
}
