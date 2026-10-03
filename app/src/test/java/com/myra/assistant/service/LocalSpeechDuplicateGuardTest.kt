package com.myra.assistant.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSpeechDuplicateGuardTest {
    @Test fun repeatedUserRequestCanSpeakAgainAfterPlaybackFinished() {
        assertFalse(LocalSpeechDuplicateGuard.shouldDrop(sameMessage = true, speechBusy = false))
    }

    @Test fun duplicateCallbackDuringActiveSpeechIsDropped() {
        assertTrue(LocalSpeechDuplicateGuard.shouldDrop(sameMessage = true, speechBusy = true))
    }
}
