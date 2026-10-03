package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceMediaLimitsTest {
    @Test fun pickerAcceptsExactlyTenPhotosWithBoundedPayload() {
        assertEquals(10, WorkspaceMediaLimits.MAX_PHOTOS)
        assertTrue(WorkspaceMediaLimits.canAddPhoto(9, 9))
        assertFalse(WorkspaceMediaLimits.canAddPhoto(10, 10))
        assertTrue(WorkspaceMediaLimits.imageEnvelopeSizes(List(10) { 1_266_668 }))
        assertFalse(WorkspaceMediaLimits.imageEnvelopeSizes(List(10) { 1_300_000 }))
    }
}
