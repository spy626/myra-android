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

    @Test fun recentLocalCacheCompletesQueryWithoutWaitingForPeriodicUpload() {
        val session = ScreenVisionSession()
        session.start(); session.setState(ScreenShareState.ACTIVE)
        val cached = requireNotNull(session.publish(byteArrayOf(7), 1_000, source = "local_cache"))
        val query = requireNotNull(session.createQuery(4, 1_300))
        val result = session.completeWithLatest(query.queryId, now = 1_350, maxAgeMs = 500)
            as FreshFrameResult.Ready
        assertEquals(cached.frameId, result.frame.frameId)
        assertEquals("local_cache", result.frame.source)
    }

    @Test fun staleCacheCannotCompleteExplicitQuery() {
        val session = ScreenVisionSession()
        session.start(); session.setState(ScreenShareState.ACTIVE)
        session.publish(byteArrayOf(7), 1_000, source = "local_cache")
        val query = requireNotNull(session.createQuery(4, 2_000))
        assertNull(session.completeWithLatest(query.queryId, now = 2_000, maxAgeMs = 500))
        assertNotNull(session.cancel(query.queryId, "fresh_capture_timeout"))
    }

    @Test fun screenQuestionsAndVisibleTargetCommandsAreDetected() {
        assertEquals(ScreenVisionIntent.ANALYZE, ScreenVisionIntentParser.parse("What is on my screen?"))
        assertEquals(ScreenVisionIntent.READ, ScreenVisionIntentParser.parse("Read this"))
        assertEquals(ScreenVisionIntent.EXPLAIN, ScreenVisionIntentParser.parse("Explain this code"))
        assertEquals(ScreenVisionIntent.CONTROL_TARGET, ScreenVisionIntentParser.parse("Center wala video open karo"))
        assertEquals(ScreenVisionIntent.CONTROL_TARGET, ScreenVisionIntentParser.parse("Open that one"))
        assertNull(ScreenVisionIntentParser.parse("I watched a video yesterday"))
        assertEquals(ScreenVisionIntent.ANALYZE, ScreenVisionIntentParser.parseStableQuery("Abhi kya dikh raha hai?"))
        assertEquals(ScreenVisionIntent.ANALYZE, ScreenVisionIntentParser.parseStableQuery("Screen pe kya dikh raha hai?"))
        assertNull(ScreenVisionIntentParser.parseStableQuery("This video is good"))
    }

    @Test fun adaptiveRoutingPrioritizesChangesWithoutBuildingATimerBacklog() {
        val policy = AdaptiveScreenRoutePolicy(changedMinIntervalMs = 500, staticKeepAliveMs = 5_000)
        assertTrue(policy.shouldRoute(100, changed = true, explicit = false))
        assertFalse(policy.shouldRoute(200, changed = true, explicit = false))
        assertTrue(policy.shouldRoute(600, changed = true, explicit = false))
        assertTrue(policy.shouldRoute(601, changed = false, explicit = true))
        assertFalse(policy.shouldRoute(700, changed = false, explicit = false))
        policy.markDirty()
        assertTrue(policy.shouldRoute(1_100, changed = false, explicit = false))
    }

    @Test fun screenMemoryRejectsSensitiveAndWeakObservations() {
        assertTrue(ScreenPrivacyPolicy.blocksLongTermMemory("OTP 123456 is visible"))
        assertTrue(ScreenPrivacyPolicy.blocksLongTermMemory("Bank account number is 123"))
        assertFalse(ScreenPrivacyPolicy.blocksLongTermMemory("Zopy is developing the LYRA Android project"))
        assertTrue(ScreenPrivacyPolicy.isMemoryWorthy("PROJECT", .94))
        assertFalse(ScreenPrivacyPolicy.isMemoryWorthy("PROJECT", .70))
        assertFalse(ScreenPrivacyPolicy.isMemoryWorthy("LIFE_EVENT", .99))
    }

    @Test fun privacyAllowsPublicPagesAndFlagsOnlyActualSensitiveValues() {
        assertNull(ScreenPrivacyPolicy.sensitiveCategory("Google results for AI news"))
        assertNull(ScreenPrivacyPolicy.sensitiveCategory("Public article: New AI model launched today"))
        assertNull(ScreenPrivacyPolicy.sensitiveCategory("YouTube video title and comments"))
        assertEquals("OTP", ScreenPrivacyPolicy.sensitiveCategory("OTP 123456"))
        assertEquals("PASSWORD", ScreenPrivacyPolicy.sensitiveCategory("Password: secret"))
        assertEquals("BANK_ACCOUNT", ScreenPrivacyPolicy.sensitiveCategory("Bank account number 1234567890"))
        assertEquals("CARD", ScreenPrivacyPolicy.sensitiveCategory("Card number 4111 1111 1111 1111"))
    }

    @Test fun armedButUndispatchedScreenQuestionStillDispatchesAtFinalBoundary() {
        assertTrue(ScreenQueryDispatchPolicy.shouldDispatch(false, dispatchedTurnId = 0, currentTurnId = 46))
        assertFalse(ScreenQueryDispatchPolicy.shouldDispatch(false, dispatchedTurnId = 46, currentTurnId = 46))
        assertFalse(ScreenQueryDispatchPolicy.shouldDispatch(true, dispatchedTurnId = 0, currentTurnId = 46))
    }

    @Test fun delayedFinalAsrDoesNotExpireArmedQuestionFromSameVoiceIdentity() {
        assertTrue(ArmedScreenQuestionPolicy.mayDispatchForIdentity(23, 23))
        assertFalse(ArmedScreenQuestionPolicy.mayDispatchForIdentity(23, 24))
        assertFalse(ArmedScreenQuestionPolicy.mayDispatchForIdentity(23, null))
    }

    @Test fun screenLatencyUsesSpeechEndOnlyFromTheSameLogicalTurn() {
        assertEquals(ScreenQuerySpeechTiming(true, 1_200), ScreenQueryTimingPolicy.bind(27, 27, 1_200))
        assertEquals(ScreenQuerySpeechTiming(false, 0), ScreenQueryTimingPolicy.bind(27, 26, 1_200))
        assertEquals(ScreenQuerySpeechTiming(false, 0), ScreenQueryTimingPolicy.bind(27, 27, 0))
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
