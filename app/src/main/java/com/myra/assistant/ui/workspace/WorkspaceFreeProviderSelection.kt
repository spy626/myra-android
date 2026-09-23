package com.myra.assistant.ui.workspace

/** Deterministic consent-gated selection for ordinary Workspace Chat only. */
internal object WorkspaceFreeProviderSelection {
    fun choose(openRouterAvailable: Boolean, groqAvailable: Boolean,
               groqFreeZdrApproved: Boolean, groqWithinBudget: Boolean,
               hasAttachments: Boolean,
               llm7Available: Boolean = false, llm7Approved: Boolean = false,
               llm7WithinBudget: Boolean = false): WorkspaceChatGateway.Provider? = when {
        hasAttachments -> if (openRouterAvailable) WorkspaceChatGateway.Provider.OPENROUTER_FREE else null
        llm7Approved -> if (llm7Available && llm7WithinBudget)
            WorkspaceChatGateway.Provider.LLM7_FREE else null
        groqFreeZdrApproved && groqAvailable && groqWithinBudget -> WorkspaceChatGateway.Provider.GROQ_FREE
        openRouterAvailable -> WorkspaceChatGateway.Provider.OPENROUTER_FREE
        else -> null
    }
}
