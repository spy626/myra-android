package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceProviderSelectionTest {
    @Test fun groqTextChosenWhenApprovedAndWithinBudget() {
        assertEquals(WorkspaceChatGateway.Provider.GROQ_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, false))
    }
    @Test fun openRouterChosenWhenGroqUnavailableOrUnapproved() {
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, false, true, false))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, false, false))
    }
    @Test fun absentKeysNeverSelectDeletedRoute() {
        assertNull(WorkspaceFreeProviderSelection.choose(false, false, false, false, false))
    }
    @Test fun llm7WinsOnlyAfterExplicitApprovalAndValidBudget() {
        assertEquals(WorkspaceChatGateway.Provider.LLM7_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, false,
                llm7Available = true, llm7Approved = true, llm7WithinBudget = true))
        assertEquals(WorkspaceChatGateway.Provider.GROQ_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, false,
                llm7Available = true, llm7Approved = false, llm7WithinBudget = true))
        assertNull(WorkspaceFreeProviderSelection.choose(true, true, true, true, false,
            llm7Available = false, llm7Approved = true, llm7WithinBudget = true))
    }

    @Test fun photosNeverGoToGroq() {
        assertNull(WorkspaceFreeProviderSelection.choose(false, true, true, true, true))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, true))
    }
    @Test fun typedMediaSelectionNeverConflatesImagesWithOriginalVideoOrAudio() {
        val media = WorkspaceProviderRegistry.AttachmentKind
        val chooseVideo = WorkspaceFreeProviderSelection.choose(true, true, true, true, true,
            attachmentKind = media.VIDEO_ORIGINAL)
        assertNull(chooseVideo)
        assertNull(WorkspaceFreeProviderSelection.choose(true, true, true, true, true,
            attachmentKind = media.AUDIO_ORIGINAL))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, true,
                attachmentKind = media.VIDEO_FRAMES_SILENT))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, true,
                attachmentKind = media.DOCUMENT_TEXT))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, true,
                attachmentKind = media.VIDEO_ORIGINAL,
                experimentalAttachmentApproved = true))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, true,
                attachmentKind = media.AUDIO_ORIGINAL,
                experimentalAttachmentApproved = true))
        assertNull(WorkspaceFreeProviderSelection.choose(false, true, true, true, true,
            attachmentKind = media.VIDEO_FRAMES_SILENT))
    }
}
