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

    @Test fun playbackLeakSpikeDoesNotBecomeBargeInButSustainedUserSpeechDoes() {
        val detector = SpeechActivityDetector(.060f, .018f, 4, 10)
        assertEquals(SpeechActivityEvent.NONE, detector.update(.09f))
        assertEquals(SpeechActivityEvent.NONE, detector.update(.01f))
        repeat(3) { assertEquals(SpeechActivityEvent.NONE, detector.update(.09f)) }
        assertEquals(SpeechActivityEvent.STARTED, detector.update(.09f))
    }

    @Test fun playbackReferenceCanRaiseThresholdWithoutDisablingRealBargeIn() {
        val detector = SpeechActivityDetector(.060f, .018f, 4, 10)
        repeat(6) { assertEquals(SpeechActivityEvent.NONE, detector.update(.05f, .08f)) }
        repeat(3) { assertEquals(SpeechActivityEvent.NONE, detector.update(.10f, .08f)) }
        assertEquals(SpeechActivityEvent.STARTED, detector.update(.10f, .08f))
    }

    @Test fun selfPlaybackGateRejectsSpeakerLeakAndAcceptsStrongNearMicInterruption() {
        val leak = SelfPlaybackBargeInGate.assess(.052f, .050f, sustained = true, enabled = true)
        assertEquals(false, leak.accepted)
        assertEquals("probable_self_playback", leak.reason)
        val user = SelfPlaybackBargeInGate.assess(.12f, .050f, sustained = true, enabled = true)
        assertEquals(true, user.accepted)
        assertEquals("independent_near_mic_speech", user.reason)
    }
}
