package com.myra.assistant.ui.workspace

/** Deterministic consent-gated selection. An enabled route never silently changes providers. */
internal object WorkspaceFreeProviderSelection {
    fun choose(openRouterAvailable: Boolean, groqAvailable: Boolean,
               groqFreeZdrApproved: Boolean, groqWithinBudget: Boolean,
               hasAttachments: Boolean, zaiApproved: Boolean = false,
               zaiAvailable: Boolean = false, zaiVisionApproved: Boolean = false,
               hasImage: Boolean = false): WorkspaceChatGateway.Provider? = when {
        // If Z.ai was explicitly selected, missing credentials or image consent stop locally.
        // Do not send the selected text/photo to a different company just because a key exists.
        zaiApproved -> when {
            !zaiAvailable -> null
            hasImage && !zaiVisionApproved -> null
            else -> WorkspaceChatGateway.Provider.ZAI_FREE
        }
        hasAttachments -> if (openRouterAvailable) WorkspaceChatGateway.Provider.OPENROUTER_FREE else null
        groqFreeZdrApproved && groqAvailable && groqWithinBudget -> WorkspaceChatGateway.Provider.GROQ_FREE
        openRouterAvailable -> WorkspaceChatGateway.Provider.OPENROUTER_FREE
        else -> null
    }
}
