package com.myra.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
}
