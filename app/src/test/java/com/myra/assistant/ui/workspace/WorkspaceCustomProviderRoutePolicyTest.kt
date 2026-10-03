package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceCustomProviderRoutePolicyTest {
    @Test fun manualRouteIsExplicitChatOnlyAndAttachmentFree() {
        assertTrue(WorkspaceCustomProviderRoutePolicy.useManualTextChat(
            enabled = true, projectType = WorkspaceProjectType.CHAT, hasAttachments = false))
        assertFalse(WorkspaceCustomProviderRoutePolicy.useManualTextChat(
            enabled = false, projectType = WorkspaceProjectType.CHAT, hasAttachments = false))
        assertFalse(WorkspaceCustomProviderRoutePolicy.useManualTextChat(
            enabled = true, projectType = WorkspaceProjectType.CHAT, hasAttachments = true))
        assertFalse(WorkspaceCustomProviderRoutePolicy.useManualTextChat(
            enabled = true, projectType = WorkspaceProjectType.WEBSITE, hasAttachments = false))
    }
}
