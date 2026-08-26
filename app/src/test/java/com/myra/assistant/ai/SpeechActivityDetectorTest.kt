package com.myra.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechActivityDetectorTest {
    @Test fun longSpeechDoesNotEndDuringVoicedFrames() {
        val detector = SpeechActivityDetector()
        assertEquals(SpeechActivityEvent.STARTED, detector.update(0.03f))
        repeat(30) { assertEquals(SpeechActivityEvent.NONE, detector.update(0.02f)) }
    }

    @Test fun speechEndsFromConsecutiveQuietMicFramesNotModelAudio() {
        val detector = SpeechActivityDetector()
        detector.update(0.03f)
        repeat(13) { assertEquals(SpeechActivityEvent.NONE, detector.update(0.001f)) }
        assertEquals(SpeechActivityEvent.ENDED, detector.update(0.001f))
    }

    @Test fun naturalShortPauseDoesNotSplitTurn() {
        val detector = SpeechActivityDetector()
        detector.update(0.03f)
        repeat(8) { detector.update(0.001f) }
        assertEquals(SpeechActivityEvent.NONE, detector.update(0.02f))
    }
}
