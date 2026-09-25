package com.myra.assistant.ui.workspace

/** Pure gate for the explicit manual Custom Provider text-chat route. */
internal object WorkspaceCustomProviderRoutePolicy {
    fun useManualTextChat(
        enabled: Boolean,
        projectType: WorkspaceProjectType?,
        hasAttachments: Boolean,
    ): Boolean = enabled && projectType == WorkspaceProjectType.CHAT && !hasAttachments
}
