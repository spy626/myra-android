package com.myra.assistant.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class LyraPlaybackCapturePolicyTest {
    @Test fun generatedVoiceIsExplicitlyCapturable() {
        assertTrue(LyraPlaybackCapturePolicy.useCapturableMediaUsage)
        assertTrue(LyraPlaybackCapturePolicy.allowExternalPlaybackCapture)
    }
}
