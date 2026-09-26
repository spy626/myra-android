package com.myra.assistant.screen

import org.junit.Assert.*
import org.junit.Test

class ScreenCommandTurnGuardTest {
    @Test fun `one turn commits only one screen command`() {
        val guard = ScreenCommandTurnGuard()
        assertTrue(guard.tryCommit(42L))
        assertFalse(guard.tryCommit(42L))
        assertTrue(guard.tryCommit(43L))
    }

    @Test fun `invalid turn cannot execute`() {
        assertFalse(ScreenCommandTurnGuard().tryCommit(0L))
    }
}
