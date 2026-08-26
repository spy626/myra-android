package com.myra.assistant.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicBargeInPolicyTest {
    @Test fun ordinaryPlaybackAllowsUserBargeInAudioToReachGemini() {
        assertTrue(MicBargeInPolicy.shouldForward(muted = false, speakerActive = true, bargeInEnabled = true))
    }

    @Test fun controlledOrMutedPlaybackCannotFeedMicIntoGeneration() {
        assertFalse(MicBargeInPolicy.shouldForward(muted = false, speakerActive = true, bargeInEnabled = false))
        assertFalse(MicBargeInPolicy.shouldForward(muted = true, speakerActive = false, bargeInEnabled = true))
    }
}
