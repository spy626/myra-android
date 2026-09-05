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
}
