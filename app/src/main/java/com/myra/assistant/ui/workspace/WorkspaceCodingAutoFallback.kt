package com.myra.assistant.ui.workspace

/** Pure opt-in/status policy. Existing Work and file owners remain authoritative. */
internal object WorkspaceCodingAutoFallback {
    const val PREFERENCE_KEY = "workspace_xkiro_free_coding_fallback_opt_in"
    fun xKiroRejected(code: Int) =
        WorkspaceProviderRegistry.definitiveFallbackAllowed(
            WorkspaceProviderRegistry.Id.XKIRO_FREE, code)
    fun openRouterRejected(code: Int) =
        WorkspaceProviderRegistry.definitiveFallbackAllowed(
            WorkspaceProviderRegistry.Id.OPENROUTER_FREE, code)
    fun displayName(endpoint: String): String? = when (endpoint) {
        WorkspaceFreeAiSuggestion.ENDPOINT -> "OpenRouter Free"
        WorkspaceGroqFree.ENDPOINT -> "Groq Free"
        else -> null
    }
    fun validKey(key: String) = key.length in 1..256 && key.none(Char::isWhitespace) && ',' !in key
    fun permitted(optIn: Boolean, xKiroEnabled: Boolean) = optIn && xKiroEnabled
    fun groqPermitted(optIn: Boolean, groqFreeZdr: Boolean, key: String) =
        optIn && groqFreeZdr && validKey(key)
}
