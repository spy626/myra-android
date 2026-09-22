package com.myra.assistant.ui.workspace

import android.content.Context
import com.myra.assistant.MyApplication

/** Only existing free Workspace providers. One key is enough to start a website task.
 * Never promote the ordinary Groq text-chat switch into website-source consent when an
 * OpenRouter key is also available. The separately approved website-source switch is
 * required before selecting Groq first with both keys.
 */
internal object WorkspaceWebsiteRoute {
    enum class Provider { OPENROUTER, GROQ, XKIRO }

    private fun valid(key: String): Boolean =
        key.length in 1..256 && key.none(Char::isWhitespace)

    /** The current website flow calls this existing three-argument entry point. Use the
     * application context (never an Activity) to read its already-saved website opt-in;
     * fail closed if the application or preference is unavailable.
     */
    fun choose(openRouterKey: String, groqKey: String, groqFreeEnabled: Boolean): Provider? =
        choose(openRouterKey, groqKey, groqFreeEnabled, approvedWebsiteSourceSharing())

    /** Pure routing policy for deterministic tests. No new provider, request or memory. */
    internal fun choose(openRouterKey: String, groqKey: String, groqFreeEnabled: Boolean,
                        websiteSourceSharingApproved: Boolean): Provider? = when {
        groqFreeEnabled && websiteSourceSharingApproved && valid(groqKey) -> Provider.GROQ
        valid(openRouterKey) -> Provider.OPENROUTER
        groqFreeEnabled && valid(groqKey) -> Provider.GROQ
        else -> null
    }

    private fun approvedWebsiteSourceSharing(): Boolean = runCatching {
        MyApplication.contextOrNull()?.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
            ?.getBoolean(WorkspaceWebsiteGroqFallback.PREFERENCE_KEY, false) == true
    }.getOrDefault(false)
}
