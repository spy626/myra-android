package com.myra.assistant.ui.workspace

/** Single deterministic, local selection policy. No automatic retry or paid route. */
internal object WorkspaceFreeProviderSelection {
    fun choose(openRouterAvailable: Boolean, groqAvailable: Boolean,
               groqFreeZdrApproved: Boolean, groqWithinBudget: Boolean,
               hasAttachments: Boolean, cloudflareAvailable: Boolean = false): WorkspaceChatGateway.Provider? = when {
        // Groq is text-only; an explicitly chosen photo/document stays off Groq.
        hasAttachments -> if (openRouterAvailable) WorkspaceChatGateway.Provider.OPENROUTER_FREE else null
        groqFreeZdrApproved && groqAvailable && groqWithinBudget ->
            WorkspaceChatGateway.Provider.GROQ_FREE
        openRouterAvailable -> WorkspaceChatGateway.Provider.OPENROUTER_FREE
        // Last-resort initial selection only; never resend after another provider fails.
        !hasAttachments && cloudflareAvailable -> WorkspaceChatGateway.Provider.CLOUDFLARE_FREE
        else -> null
    }
}
