package com.myra.assistant.ai

import org.junit.Assert.*
import org.junit.Test

class GeminiConnectionStateMachineTest {
    @Test fun `old callback cannot change current generation`() {
        val machine = GeminiConnectionStateMachine()
        val old = machine.beginConnect(10L, false)
        val current = machine.beginConnect(20L, true)
        assertFalse(machine.markConnected(old.generation, 30L))
        assertTrue(machine.markConnected(current.generation, 30L))
        assertEquals(GeminiConnectionState.CONNECTED, machine.snapshot().state)
    }

    @Test fun `successful reconnect resets retry budget`() {
        val machine = GeminiConnectionStateMachine()
        val first = machine.beginConnect(10L, false)
        assertEquals(1, machine.markFailure(first.generation))
        val retry = machine.beginConnect(20L, true)
        assertTrue(machine.markConnected(retry.generation, 30L))
        assertEquals(0, machine.snapshot().retryAttempt)
    }

    @Test fun `backoff is bounded exponential with jitter`() {
        val machine = GeminiConnectionStateMachine()
        val delays = (1..5).map { machine.backoffMs(it, 7) }
        assertTrue(delays.zipWithNext().all { (a, b) -> b > a })
        assertTrue(delays.last() <= 8_250L)
    }

    @Test fun `retry budget becomes failed instead of looping forever`() {
        val machine = GeminiConnectionStateMachine(maxRetries = 1)
        val attempt = machine.beginConnect(10L, false)
        assertEquals(1, machine.markFailure(attempt.generation))
        assertNull(machine.markFailure(attempt.generation))
        assertEquals(GeminiConnectionState.FAILED, machine.snapshot().state)
    }
}
