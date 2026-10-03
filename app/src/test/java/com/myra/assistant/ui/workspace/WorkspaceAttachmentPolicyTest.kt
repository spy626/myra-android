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

    @Test fun mediaCanUseExplicitlyConsentedZeroPriceProviderOnly() {
        assertTrue(WorkspaceAttachmentPolicy.sendableNow(WorkspaceAttachmentPolicy.Kind.IMAGE))
        assertTrue(WorkspaceAttachmentPolicy.sendableNow(WorkspaceAttachmentPolicy.Kind.TEXT))
        assertTrue(WorkspaceAttachmentPolicy.sendableNow(WorkspaceAttachmentPolicy.Kind.AUDIO))
        assertTrue(WorkspaceAttachmentPolicy.sendableNow(WorkspaceAttachmentPolicy.Kind.VIDEO))
        assertEquals(WorkspaceMediaLimits.MAX_AUDIO_BYTES.toLong(),
            WorkspaceAttachmentPolicy.maxBytes(WorkspaceAttachmentPolicy.Kind.AUDIO))
        assertEquals(WorkspaceMediaLimits.MAX_NATIVE_VIDEO_BYTES.toLong(),
            WorkspaceAttachmentPolicy.maxBytes(WorkspaceAttachmentPolicy.Kind.VIDEO))
    }
}
