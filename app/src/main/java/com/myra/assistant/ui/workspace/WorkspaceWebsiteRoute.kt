package com.myra.assistant.ui.workspace

/** Only existing free Workspace providers. One key is enough to start a website task.
 * Cross-company source forwarding still uses the existing saved website opt-in.
 */
internal object WorkspaceWebsiteRoute {
    enum class Provider { OPENROUTER, GROQ }

    private fun valid(key: String): Boolean =
        key.length in 1..256 && key.none(Char::isWhitespace)

    fun choose(openRouterKey: String, groqKey: String, groqFreeEnabled: Boolean): Provider? = when {
        valid(openRouterKey) -> Provider.OPENROUTER
        groqFreeEnabled && valid(groqKey) -> Provider.GROQ
        else -> null
    }
}
