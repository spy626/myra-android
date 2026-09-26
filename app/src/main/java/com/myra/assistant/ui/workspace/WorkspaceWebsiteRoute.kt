package com.myra.assistant.ui.workspace

import android.content.Context
import com.myra.assistant.MyApplication

/** Only existing free Workspace providers. One key is enough to start a website task.
 * Never promote ordinary chat consent into project-source consent.
 */
internal object WorkspaceWebsiteRoute {
    enum class Provider { OPENROUTER, GROQ, XKIRO, ZAI }

    private fun valid(key: String): Boolean =
        key.length in 1..256 && key.none(Char::isWhitespace)

    fun choose(openRouterKey: String, groqKey: String, groqFreeEnabled: Boolean): Provider? =
        choose(openRouterKey, groqKey, groqFreeEnabled, approvedWebsiteSourceSharing())

    internal fun choose(openRouterKey: String, groqKey: String, groqFreeEnabled: Boolean,
                        websiteSourceSharingApproved: Boolean): Provider? = when {
        groqFreeEnabled && websiteSourceSharingApproved && valid(groqKey) -> Provider.GROQ
        valid(openRouterKey) -> Provider.OPENROUTER
        groqFreeEnabled && valid(groqKey) -> Provider.GROQ
        else -> null
    }

    /** Explicit Z.ai Work coding consent is sticky: invalid/missing credentials stop
     * locally instead of silently sharing source with another company.
     */
    internal fun choose(openRouterKey: String, groqKey: String, groqFreeEnabled: Boolean,
                        websiteSourceSharingApproved: Boolean, zaiKey: String,
                        zaiCodingApproved: Boolean): Provider? {
        if (zaiCodingApproved) return if (valid(zaiKey)) Provider.ZAI else null
        return choose(openRouterKey, groqKey, groqFreeEnabled, websiteSourceSharingApproved)
    }

    private fun approvedWebsiteSourceSharing(): Boolean = runCatching {
        MyApplication.contextOrNull()?.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
            ?.getBoolean(WorkspaceWebsiteGroqFallback.PREFERENCE_KEY, false) == true
    }.getOrDefault(false)
}
