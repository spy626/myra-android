package com.myra.assistant.ui.workspace

/** Deterministic consent-gated selection for ordinary Workspace Chat only. */
internal object WorkspaceFreeProviderSelection {
    fun choose(openRouterAvailable: Boolean, groqAvailable: Boolean,
               groqFreeZdrApproved: Boolean, groqWithinBudget: Boolean,
               hasAttachments: Boolean): WorkspaceChatGateway.Provider? = when {
        hasAttachments -> if (openRouterAvailable) WorkspaceChatGateway.Provider.OPENROUTER_FREE else null
        groqFreeZdrApproved && groqAvailable && groqWithinBudget -> WorkspaceChatGateway.Provider.GROQ_FREE
        openRouterAvailable -> WorkspaceChatGateway.Provider.OPENROUTER_FREE
        else -> null
    }
}
