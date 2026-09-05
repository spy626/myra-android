package com.myra.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceTurnIdentityTest {
    @Test fun partial_final_visual_work_inherits_one_speech_identity() {
        val store = VoiceTurnIdentityStore()
        store.begin(14L, 1_000L)
        store.speechEnded(14L, 1_850L)
        store.finalTranscript(14L, "session:14")
        val identity = store.current()!!
        assertTrue(identity.consistent)
        assertEquals(14L, identity.userTurnId)
        assertEquals(14L, identity.transcriptTurnId)
        assertEquals(1_850L, identity.speechEndAt)
        assertEquals("session:14", identity.finalTranscriptId)
    }

    @Test fun stale_final_transcript_cannot_rebind_current_speech_turn() {
        val store = VoiceTurnIdentityStore()
        store.begin(15L, 2_000L)
        assertNull(store.finalTranscript(14L, "session:14"))
        assertEquals(15L, store.current()?.userTurnId)
        assertNull(store.current()?.finalTranscriptId)
    }

    @Test fun genuineVadTurnCannotStartWithZeroIdentity() {
        val store = VoiceTurnIdentityStore()
        try {
            store.begin(0L, 2_000L)
            fail("zero turn must be rejected")
        } catch (_: IllegalArgumentException) {
            assertNull(store.current())
        }
    }

    @Test fun preFinalScrollCandidateDoesNotExecuteAndBindsOnlyToItsFinalTurn() {
        val candidates = PendingScrollCandidateStore()
        candidates.stage(7L, "DOWN", 1_000L)
        assertEquals("DOWN", candidates.current()?.direction)
        assertNull(candidates.consume(8L))
        assertEquals(7L, candidates.current()?.turnId)
        assertEquals("DOWN", candidates.consume(7L)?.direction)
        assertNull(candidates.current())
    }

    @Test fun completedCandidateCannotBeReusedByLaterTurn() {
        val candidates = PendingScrollCandidateStore()
        candidates.stage(7L, "DOWN", 1_000L)
        assertEquals(7L, candidates.consume(7L)?.turnId)
        assertNull(candidates.consume(8L))
    }

    @Test fun runtimeActionRequiresExactNonZeroTurnAndTaskIdentity() {
        assertTrue(RuntimeActionBindingGuard.matches(7L, "task-7", 7L, "task-7"))
        assertTrue(!RuntimeActionBindingGuard.matches(8L, "task-7", 7L, "task-7"))
        assertTrue(!RuntimeActionBindingGuard.matches(7L, "task-8", 7L, "task-7"))
        assertTrue(!RuntimeActionBindingGuard.matches(0L, "task-7", 7L, "task-7"))
    }

    @Test fun zeroIdSpeechEndCannotReplaceAuthorizedIdentity() {
        val store = VoiceTurnIdentityStore()
        store.begin(7L, 1_000L)
        assertNull(store.speechEnded(0L, 1_500L))
        assertEquals(7L, store.current()?.userTurnId)
        assertEquals(0L, store.current()?.speechEndAt)
        store.speechEnded(store.current()!!.userTurnId, 1_500L)
        assertEquals(1_500L, store.current()?.speechEndAt)
    }

    @Test fun independentVadUtterancesReceiveIndependentTurnOwnership() {
        val store = VoiceTurnIdentityStore()
        store.begin(18L, 1_000L)
        store.speechEnded(18L, 1_500L)
        store.finalTranscript(18L, "session:18")

        store.begin(19L, 2_000L)

        val second = store.current()!!
        assertEquals(19L, second.userTurnId)
        assertEquals(19L, second.transcriptTurnId)
        assertEquals(0L, second.speechEndAt)
        assertNull(second.finalTranscriptId)
    }

    @Test fun multiplePreFinalProposalsMergeWithoutConsumingTheTurn() {
        val candidates = PendingScrollCandidateStore()
        candidates.stage(5L, "DOWN", 1_000L, source = "gemini_phone_tool", foregroundPackage = "chrome", windowId = 7)
        candidates.stage(5L, "DOWN", 1_100L, source = "partial_transcript", foregroundPackage = "chrome", windowId = 7)
        assertEquals(5L, candidates.current()?.turnId)
        assertEquals(1_100L, candidates.current()?.detectedAt)
        assertEquals("partial_transcript", candidates.current()?.source)
        assertEquals(5L, candidates.consume(5L)?.turnId)
        assertNull(candidates.consume(5L))
    }

    @Test fun stagedCandidateRequiresFreshCompatibleFinalScreen() {
        val candidate = PendingScrollCandidate(
            5L, "DOWN", 1_000L, "gemini_phone_tool", "chrome", 7, 20L
        )
        assertTrue(ScrollCandidatePolicy.compatible(candidate, 5L, "chrome", 7, 2_000L))
        assertTrue(!ScrollCandidatePolicy.compatible(candidate, 6L, "chrome", 7, 2_000L))
        assertTrue(!ScrollCandidatePolicy.compatible(candidate, 5L, "youtube", 7, 2_000L))
        assertTrue(!ScrollCandidatePolicy.compatible(candidate, 5L, "chrome", 8, 2_000L))
        assertTrue(!ScrollCandidatePolicy.compatible(candidate, 5L, "chrome", 7, 20_000L))
    }

    @Test fun conversationalFinalDiscardsItsStagedCandidate() {
        val candidates = PendingScrollCandidateStore()
        candidates.stage(5L, "DOWN", 1_000L, source = "gemini_phone_tool")
        candidates.discardForTurn(5L)
        assertNull(candidates.current())
    }

    @Test fun independentVadCycleCannotReuseAnUnfinalizedTurnIndefinitely() {
        assertTrue(SpeechCycleBoundaryPolicy.startsNewTurn(
            activeTurnId = 6L,
            previousSpeechEndedAt = 1_000L,
            newSpeechStartedAt = 2_000L,
            transcriptStarted = false
        ))
        assertTrue(!SpeechCycleBoundaryPolicy.startsNewTurn(
            activeTurnId = 6L,
            previousSpeechEndedAt = 1_000L,
            newSpeechStartedAt = 1_200L,
            transcriptStarted = false
        ))
        assertTrue(!SpeechCycleBoundaryPolicy.startsNewTurn(
            activeTurnId = 6L,
            previousSpeechEndedAt = 1_000L,
            newSpeechStartedAt = 2_000L,
            transcriptStarted = true
        ))
    }
}
