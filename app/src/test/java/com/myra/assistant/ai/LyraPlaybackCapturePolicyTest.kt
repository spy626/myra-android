package com.myra.assistant.ai

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class LyraPlaybackCapturePolicyTest {
    @Test fun generatedVoiceIsExplicitlyCapturable() {
        assertTrue(LyraPlaybackCapturePolicy.useCapturableMediaUsage)
        assertTrue(LyraPlaybackCapturePolicy.allowExternalPlaybackCapture)
    }

    @Test fun externalMediaBlocksTheFirstUnapprovedAudioChunk() {
        assertFalse(LyraPlaybackCapturePolicy.shouldAcceptModelAudio(
            suppressed = false,
            assistantAlreadySpeaking = false,
            mediaGuardAllowsResponse = false
        ))
    }

    @Test fun acceptedAssistantTurnContinuesWhileItsMediaTrackIsActive() {
        assertTrue(LyraPlaybackCapturePolicy.shouldAcceptModelAudio(
            suppressed = false,
            assistantAlreadySpeaking = true,
            mediaGuardAllowsResponse = false
        ))
    }

    @Test fun suppressedTurnNeverPlaysAudio() {
        assertFalse(LyraPlaybackCapturePolicy.shouldAcceptModelAudio(
            suppressed = true,
            assistantAlreadySpeaking = true,
            mediaGuardAllowsResponse = true
        ))
    }
}
