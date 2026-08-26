package com.myra.assistant.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnResponseArbiterTest {
    @Test fun controlledTurnRejectsOrdinaryAudioUntilGenerationAndPlaybackComplete() {
        val arbiter = TurnResponseArbiter()
        arbiter.begin(7)
        arbiter.claimControlled(7)
        assertFalse(arbiter.acceptsOrdinaryModel())
        arbiter.controlledPlaybackComplete()
        assertFalse(arbiter.releaseIfComplete())
        assertFalse(arbiter.acceptsOrdinaryModel())
        arbiter.controlledGenerationComplete()
        assertTrue(arbiter.releaseIfComplete())
        assertTrue(arbiter.acceptsOrdinaryModel())
    }

    @Test fun lateOrdinaryAudioCannotTakeControlledTurn() {
        val arbiter = TurnResponseArbiter()
        arbiter.claimControlled(11)
        repeat(20) { assertFalse(arbiter.acceptsOrdinaryModel()) }
    }
}
