package com.myra.assistant.screen

import org.junit.Assert.*
import org.junit.Test

class ScreenVisionPolicyTest {
    @Test fun screenResponseStaysBoundToOriginalTurnQuerySessionAndOneNewGeneration() {
        val binding = ScreenResponseBinding(43, "query-1", "session-1", generationFloor = 46)
        assertFalse(binding.acceptsGeneration(46))
        assertTrue(binding.acceptsGeneration(47))
        assertFalse(binding.acceptsGeneration(48))
        assertTrue(binding.matches("query-1", "session-1", 43))
        assertFalse(binding.matches("query-1", "session-1", 44))
        assertFalse(binding.matches("stale-query", "session-1", 43))
    }
    @Test fun explicitQueryUsesCurrentSessionFrameAndStopInvalidatesEverything() {
        val session = ScreenVisionSession()
        val firstSession = session.start()
        session.setState(ScreenShareState.ACTIVE)
        val periodic = requireNotNull(session.publish(byteArrayOf(1), 100, source = "passive"))
        val query = requireNotNull(session.createQuery(7, 110))
        val fresh = requireNotNull(session.publish(byteArrayOf(2), 120, source = "explicit_query"))
        val result = session.complete(query.queryId, fresh) as FreshFrameResult.Ready
        assertEquals(firstSession, result.frame.sessionId)
        assertTrue(result.frame.frameId > periodic.frameId)
        assertArrayEquals(byteArrayOf(2), result.frame.bytes)

        val pending = requireNotNull(session.createQuery(8, 130))
        assertEquals(1, session.invalidate(ScreenShareState.STOPPED).count { it.queryId == pending.queryId })
        assertNull(session.latestFrame)
        assertFalse(session.isCurrent(firstSession))
        assertNull(session.complete(pending.queryId, fresh))
        assertNull(session.createQuery(9, 140))
    }

    @Test fun frameFromPreviousSessionCannotCompleteNewQuery() {
        val session = ScreenVisionSession()
        session.start(); session.setState(ScreenShareState.ACTIVE)
        val old = requireNotNull(session.publish(byteArrayOf(1), 1, source = "passive"))
        session.invalidate(ScreenShareState.STOPPED)
        session.start(); session.setState(ScreenShareState.ACTIVE)
        val query = requireNotNull(session.createQuery(2, 2))
        assertTrue(session.complete(query.queryId, old) is FreshFrameResult.Unavailable)
    }

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
