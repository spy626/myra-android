package com.myra.assistant.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicBargeInPolicyTest {
    @Test fun ordinaryPlaybackAllowsUserBargeInAudioToReachGemini() {
        assertTrue(MicBargeInPolicy.shouldForward(false, true, true, confirmedPlaybackBargeIn = true))
    }

    @Test fun playbackAloneCannotReachGeminiBeforeIndependentBargeInConfirmation() {
        assertFalse(MicBargeInPolicy.shouldForward(false, true, true, confirmedPlaybackBargeIn = false))
    }

    @Test fun controlledOrMutedPlaybackCannotFeedMicIntoGeneration() {
        assertFalse(MicBargeInPolicy.shouldForward(muted = false, speakerActive = true, bargeInEnabled = false))
        assertFalse(MicBargeInPolicy.shouldForward(muted = true, speakerActive = false, bargeInEnabled = true))
    }
}
