package com.myra.assistant.data.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnclearDeleteIntentGuardTest {
    @Test fun garbledDeleteAsksForClarificationInsteadOfGuessingUninstall() {
        assertTrue(UnclearDeleteIntentGuard.needsClarification("Now barcode delete kar do"))
        assertTrue(UnclearDeleteIntentGuard.needsClarification("uninstall Instagram"))
    }

    @Test fun validMemoryDeleteContinuesToMemoryParser() {
        assertFalse(UnclearDeleteIntentGuard.needsClarification("Noval ko delete kar do"))
    }
}
