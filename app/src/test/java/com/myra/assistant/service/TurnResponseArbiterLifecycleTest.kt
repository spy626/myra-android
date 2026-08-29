package com.myra.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnResponseArbiterLifecycleTest {
    @Test fun newer_turn_gets_new_generation_and_old_generation_is_rejected() {
        val arbiter = TurnResponseArbiter()
        arbiter.begin(1L)
        val first = arbiter.generationId
        assertEquals(ResponseLifecycle.THINKING, arbiter.lifecycle)
        arbiter.begin(2L)
        val second = arbiter.generationId
        assertTrue(second > first)
        assertFalse(arbiter.isCurrent(1L, first))
        assertTrue(arbiter.isCurrent(2L, second))
    }

    @Test fun controlled_response_has_single_generation_owner() {
        val arbiter = TurnResponseArbiter()
        arbiter.claimControlled(7L)
        val generation = arbiter.generationId
        assertEquals(ResponseOwner.CONTROLLED_LOCAL, arbiter.owner)
        assertEquals(ResponseLifecycle.GENERATING_VOICE, arbiter.lifecycle)
        assertTrue(arbiter.markVoicePlaying(7L, generation))
        assertEquals(ResponseLifecycle.PLAYING, arbiter.lifecycle)
        assertTrue(arbiter.markGenerationComplete(7L, generation))
        assertTrue(arbiter.markPlaybackComplete(7L, generation))
        assertEquals(ResponseLifecycle.FINISHED, arbiter.lifecycle)
    }

    @Test fun invalidated_generation_cannot_resume_playback() {
        val arbiter = TurnResponseArbiter()
        arbiter.begin(3L)
        val generation = arbiter.generationId
        arbiter.invalidateCurrent()
        assertFalse(arbiter.markVoicePlaying(3L, generation))
    }
}
