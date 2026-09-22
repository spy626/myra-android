package com.myra.assistant.ui.workspace

/** Deterministic, consent-gated selection; no implicit cross-provider switch. */
internal object WorkspaceFreeProviderSelection {
    fun choose(openRouterAvailable: Boolean, groqAvailable: Boolean,
               groqFreeZdrApproved: Boolean, groqWithinBudget: Boolean,
               hasAttachments: Boolean, zaiApproved: Boolean = false,
               zaiAvailable: Boolean = false, zaiVisionApproved: Boolean = false,
               hasImage: Boolean = false): WorkspaceChatGateway.Provider? = when {
        // Photo needs separate consent. If not granted, preserve existing OpenRouter route.
        hasImage && zaiApproved && zaiAvailable && zaiVisionApproved -> WorkspaceChatGateway.Provider.ZAI_FREE
        hasAttachments -> if (openRouterAvailable) WorkspaceChatGateway.Provider.OPENROUTER_FREE
            else if (zaiApproved && zaiAvailable && !hasImage) WorkspaceChatGateway.Provider.ZAI_FREE else null
        zaiApproved && zaiAvailable -> WorkspaceChatGateway.Provider.ZAI_FREE
        groqFreeZdrApproved && groqAvailable && groqWithinBudget -> WorkspaceChatGateway.Provider.GROQ_FREE
        openRouterAvailable -> WorkspaceChatGateway.Provider.OPENROUTER_FREE
        else -> null
    }
}
