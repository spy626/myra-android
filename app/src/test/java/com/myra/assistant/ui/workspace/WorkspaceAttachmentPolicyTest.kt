package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceAttachmentPolicyTest {
    @Test fun classifiesSupportedAttachmentFamilies() {
        assertEquals(WorkspaceAttachmentPolicy.Kind.IMAGE,
            WorkspaceAttachmentPolicy.kind("image/jpeg"))
        assertEquals(WorkspaceAttachmentPolicy.Kind.TEXT,
            WorkspaceAttachmentPolicy.kind("text/plain"))
        assertEquals(WorkspaceAttachmentPolicy.Kind.TEXT,
            WorkspaceAttachmentPolicy.kind("text/markdown"))
        assertEquals(WorkspaceAttachmentPolicy.Kind.TEXT,
            WorkspaceAttachmentPolicy.kind("text/x-markdown"))
        assertEquals(WorkspaceAttachmentPolicy.Kind.AUDIO,
            WorkspaceAttachmentPolicy.kind("audio/mpeg"))
        assertEquals(WorkspaceAttachmentPolicy.Kind.VIDEO,
            WorkspaceAttachmentPolicy.kind("video/mp4"))
        assertEquals(WorkspaceAttachmentPolicy.Kind.UNSUPPORTED,
            WorkspaceAttachmentPolicy.kind("application/zip"))
    }

    @Test fun audioAndVideoStayLocalUntilExplicitProviderSupportExists() {
        assertTrue(WorkspaceAttachmentPolicy.sendableNow(WorkspaceAttachmentPolicy.Kind.IMAGE))
        assertTrue(WorkspaceAttachmentPolicy.sendableNow(WorkspaceAttachmentPolicy.Kind.TEXT))
        assertFalse(WorkspaceAttachmentPolicy.sendableNow(WorkspaceAttachmentPolicy.Kind.AUDIO))
        assertFalse(WorkspaceAttachmentPolicy.sendableNow(WorkspaceAttachmentPolicy.Kind.VIDEO))
    }
}
