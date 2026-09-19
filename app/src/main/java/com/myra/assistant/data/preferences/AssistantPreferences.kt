package com.myra.assistant.data.preferences

import android.content.Context

class AssistantPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("myra", Context.MODE_PRIVATE)
    var userName: String
        get() = preferences.getString("user_name", "Zopy").orEmpty().ifBlank { "Zopy" }
        set(value) { preferences.edit().putString("user_name", value.trim().ifBlank { "Zopy" }).apply() }
    var continuousListening: Boolean
        get() = preferences.getBoolean("continuous_listening", true)
        set(value) { preferences.edit().putBoolean("continuous_listening", value).apply() }
}
