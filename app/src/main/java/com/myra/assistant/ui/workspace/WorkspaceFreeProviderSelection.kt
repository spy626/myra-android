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
    /**
     * Source-bearing research is not ordinary Chat: never send website passages
     * to the sticky LLM7 text route (sourceAllowed=false), xKiro coding, or
     * Z.ai without verified zero-cost/source-consent gates.
     *
     * The caller evaluates Groq's budget against the EXACT one-turn research
     * envelope rather than old private conversation history, and excludes
     * providers on a confirmed rate-limit cooldown before calling this.
     * One approved primary route only, no extra AI classifier or retry loop.
     */
    fun chooseResearch(
        openRouterReady: Boolean,
        groqReady: Boolean,
        groqFreeZdrApproved: Boolean,
        groqFitsResearchEnvelope: Boolean,
    ): WorkspaceChatGateway.Provider? {
        val groq = WorkspaceProviderRegistry.Id.GROQ_FREE
        val open = WorkspaceProviderRegistry.Id.OPENROUTER_FREE
        return when {
            groqReady && groqFreeZdrApproved && groqFitsResearchEnvelope &&
                WorkspaceProviderRegistry.supports(groq, WorkspaceProviderRegistry.TaskKind.RESEARCH) &&
                WorkspaceProviderRegistry.allowsSource(groq) ->
                WorkspaceChatGateway.Provider.GROQ_FREE
            openRouterReady &&
                WorkspaceProviderRegistry.supports(open, WorkspaceProviderRegistry.TaskKind.RESEARCH) &&
                WorkspaceProviderRegistry.allowsSource(open) &&
                WorkspaceProviderRegistry.capability(open).freeAssurance ==
                    WorkspaceProviderRegistry.FreeAssurance.API_ZERO_PRICE_CEILING ->
                WorkspaceChatGateway.Provider.OPENROUTER_FREE
            else -> null
        }
    }

    fun choose(openRouterAvailable: Boolean, groqAvailable: Boolean,
               groqFreeZdrApproved: Boolean, groqWithinBudget: Boolean,
               hasAttachments: Boolean,
               llm7Available: Boolean = false, llm7Approved: Boolean = false,
               llm7WithinBudget: Boolean = false,
               attachmentKind: WorkspaceProviderRegistry.AttachmentKind =
                   WorkspaceProviderRegistry.AttachmentKind.PHOTO,
               experimentalAttachmentApproved: Boolean = false): WorkspaceChatGateway.Provider? = when {
        hasAttachments -> if (openRouterAvailable &&
            WorkspaceProviderRegistry.supportsAttachment(
                WorkspaceProviderRegistry.Id.OPENROUTER_FREE, attachmentKind,
                experimentalAttachmentApproved))
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
