package com.myra.assistant.ui.workspace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores only non-secret Custom Provider metadata.
 * API keys remain exclusively in ApiKeyStore's encrypted storage.
 */
internal object WorkspaceCustomProviderStore {
    private const val PREFERENCES = "workspace_ui"
    private const val PROFILE_KEY = "workspace_custom_provider_profile_v1"
    const val DEFAULT_PROFILE_ID = "custom_manual"
    const val CHAT_PREFERENCE_KEY = "workspace_custom_provider_manual_chat"

    fun encode(profile: WorkspaceCustomProviderProfile.Validated): String =
        JSONObject()
            .put("v", 1)
            .put("id", profile.id)
            .put("displayName", profile.displayName)
            .put("baseUrl", profile.baseUrl)
            .put("modelId", profile.modelId)
            .put("authMode", profile.authMode.name)
            .put("localEndpoint", profile.localEndpoint)
            .put("tasks", JSONArray(profile.tasks.map { it.name }.sorted()))
            .put("maxPromptChars", profile.maxPromptChars)
            .put("maxOutputTokens", profile.maxOutputTokens)
            .put("sourceAllowed", profile.sourceAllowed)
            .put("attachmentsAllowed", profile.attachmentsAllowed)
            .put("timeoutSeconds", profile.timeoutSeconds)
            .put("costState", profile.costState.name)
            .put("automaticRouting", profile.automaticRouting)
            .toString()

    fun decode(raw: String): WorkspaceCustomProviderProfile.Validated? = runCatching {
        val root = JSONObject(raw)
        require(root.optInt("v") == 1) { "Unsupported Custom provider profile version" }
        val tasksArray = root.getJSONArray("tasks")
        val tasks = buildSet {
            for (i in 0 until tasksArray.length()) {
                add(WorkspaceProviderRegistry.TaskKind.valueOf(tasksArray.getString(i)))
            }
        }
        WorkspaceCustomProviderProfile.validate(
            WorkspaceCustomProviderProfile.Draft(
                id = root.getString("id"),
                displayName = root.getString("displayName"),
                baseUrl = root.getString("baseUrl"),
                modelId = root.getString("modelId"),
                authMode = WorkspaceCustomProviderProfile.AuthMode.valueOf(
                    root.getString("authMode")),
                localEndpoint = root.getBoolean("localEndpoint"),
                tasks = tasks,
                maxPromptChars = root.getInt("maxPromptChars"),
                maxOutputTokens = root.getInt("maxOutputTokens"),
                sourceAllowed = root.getBoolean("sourceAllowed"),
                attachmentsAllowed = root.getBoolean("attachmentsAllowed"),
                timeoutSeconds = root.getInt("timeoutSeconds"),
                costState = WorkspaceCustomProviderProfile.CostState.valueOf(
                    root.getString("costState")),
                automaticRouting = root.getBoolean("automaticRouting"),
            )
        )
    }.getOrNull()

    fun load(context: Context): WorkspaceCustomProviderProfile.Validated? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(PROFILE_KEY, null)?.let(::decode)

    fun save(context: Context, profile: WorkspaceCustomProviderProfile.Validated) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(PROFILE_KEY, encode(profile)).apply()
    }

    fun chatEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(CHAT_PREFERENCE_KEY, false)

    fun setChatEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(CHAT_PREFERENCE_KEY, enabled).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(PROFILE_KEY).remove(CHAT_PREFERENCE_KEY).apply()
    }
}
