package com.myra.assistant.screen

import org.junit.Assert.*
import org.junit.Test

class ScreenVisionPolicyTest {
    @Test fun screenQuestionsAndVisibleTargetCommandsAreDetected() {
        assertEquals(ScreenVisionIntent.ANALYZE, ScreenVisionIntentParser.parse("What is on my screen?"))
        assertEquals(ScreenVisionIntent.READ, ScreenVisionIntentParser.parse("Read this"))
        assertEquals(ScreenVisionIntent.EXPLAIN, ScreenVisionIntentParser.parse("Explain this code"))
        assertEquals(ScreenVisionIntent.CONTROL_TARGET, ScreenVisionIntentParser.parse("Center wala video open karo"))
        assertEquals(ScreenVisionIntent.CONTROL_TARGET, ScreenVisionIntentParser.parse("Open that one"))
        assertNull(ScreenVisionIntentParser.parse("I watched a video yesterday"))
    }

    @Test fun screenMemoryRejectsSensitiveAndWeakObservations() {
        assertTrue(ScreenPrivacyPolicy.blocksLongTermMemory("OTP 123456 is visible"))
        assertTrue(ScreenPrivacyPolicy.blocksLongTermMemory("Bank account number is 123"))
        assertFalse(ScreenPrivacyPolicy.blocksLongTermMemory("Zopy is developing the LYRA Android project"))
        assertTrue(ScreenPrivacyPolicy.isMemoryWorthy("PROJECT", .94))
        assertFalse(ScreenPrivacyPolicy.isMemoryWorthy("PROJECT", .70))
        assertFalse(ScreenPrivacyPolicy.isMemoryWorthy("LIFE_EVENT", .99))
    }

    @Test fun sharingLifecycleRejectsImpossibleTransitions() {
        val machine = ScreenShareStateMachine()
        assertFalse(machine.transition(ScreenShareState.ACTIVE))
        assertTrue(machine.transition(ScreenShareState.REQUESTING_PERMISSION))
        assertTrue(machine.transition(ScreenShareState.ACTIVE))
        assertTrue(machine.transition(ScreenShareState.PAUSED))
        assertTrue(machine.transition(ScreenShareState.RESUMING))
        assertTrue(machine.transition(ScreenShareState.ACTIVE))
        assertTrue(machine.transition(ScreenShareState.STOPPING))
        assertTrue(machine.transition(ScreenShareState.STOPPED))
        assertFalse(machine.transition(ScreenShareState.ACTIVE))
    }
}
