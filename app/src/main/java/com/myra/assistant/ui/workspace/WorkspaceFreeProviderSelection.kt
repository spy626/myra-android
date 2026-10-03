package com.myra.assistant.ui.workspace

/** Deterministic consent-gated selection for ordinary Workspace Chat only. */
internal object WorkspaceFreeProviderSelection {
    private fun usable(provider: WorkspaceChatGateway.Provider, attachments: Boolean): Boolean {
        val id = WorkspaceProviderRegistry.id(provider)
        val task = if (attachments) WorkspaceProviderRegistry.TaskKind.CHAT_ATTACHMENT
            else WorkspaceProviderRegistry.TaskKind.CHAT_TEXT
        return WorkspaceProviderRegistry.supports(id, task) &&
            (!attachments || WorkspaceProviderRegistry.allowsAttachments(id))
    }
    fun choose(openRouterAvailable: Boolean, groqAvailable: Boolean,
               groqFreeZdrApproved: Boolean, groqWithinBudget: Boolean,
               hasAttachments: Boolean,
               llm7Available: Boolean = false, llm7Approved: Boolean = false,
               llm7WithinBudget: Boolean = false): WorkspaceChatGateway.Provider? = when {
        hasAttachments -> if (openRouterAvailable &&
            usable(WorkspaceChatGateway.Provider.OPENROUTER_FREE, attachments = true))
            WorkspaceChatGateway.Provider.OPENROUTER_FREE else null
        llm7Approved -> if (llm7Available && llm7WithinBudget &&
            usable(WorkspaceChatGateway.Provider.LLM7_FREE, attachments = false))
            WorkspaceChatGateway.Provider.LLM7_FREE else null
        groqFreeZdrApproved && groqAvailable && groqWithinBudget &&
            usable(WorkspaceChatGateway.Provider.GROQ_FREE, attachments = false) ->
            WorkspaceChatGateway.Provider.GROQ_FREE
        openRouterAvailable && usable(WorkspaceChatGateway.Provider.OPENROUTER_FREE, attachments = false) ->
            WorkspaceChatGateway.Provider.OPENROUTER_FREE
        else -> null
    }
}
