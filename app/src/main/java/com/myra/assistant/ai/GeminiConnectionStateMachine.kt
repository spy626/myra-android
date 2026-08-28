package com.myra.assistant.ai

enum class GeminiConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DEGRADED, RECONNECTING, STOPPING, FAILED }

data class GeminiConnectionSnapshot(
    val state: GeminiConnectionState = GeminiConnectionState.DISCONNECTED,
    val generation: Int = 0,
    val connectionAttemptId: Long = 0L,
    val retryAttempt: Int = 0,
    val startedAt: Long = 0L,
    val stableSince: Long = 0L
)

/** Pure ownership policy. Scheduling and sockets remain in GeminiLiveClient. */
class GeminiConnectionStateMachine(private val maxRetries: Int = 5) {
    @Volatile private var snapshot = GeminiConnectionSnapshot()

    fun snapshot(): GeminiConnectionSnapshot = snapshot

    @Synchronized fun beginConnect(now: Long, reconnect: Boolean): GeminiConnectionSnapshot {
        val nextGeneration = snapshot.generation + 1
        snapshot = snapshot.copy(
            state = if (reconnect) GeminiConnectionState.RECONNECTING else GeminiConnectionState.CONNECTING,
            generation = nextGeneration,
            connectionAttemptId = snapshot.connectionAttemptId + 1,
            startedAt = now,
            stableSince = 0L
        )
        return snapshot
    }

    @Synchronized fun markSocketConnecting(generation: Int): Boolean = updateIfCurrent(generation) {
        it.copy(state = GeminiConnectionState.CONNECTING)
    }

    @Synchronized fun markConnected(generation: Int, now: Long): Boolean = updateIfCurrent(generation) {
        it.copy(state = GeminiConnectionState.CONNECTED, retryAttempt = 0, stableSince = now)
    }

    /** Returns the retry number, or null for a stale callback / exhausted budget. */
    @Synchronized fun markFailure(generation: Int): Int? {
        if (generation != snapshot.generation || snapshot.state == GeminiConnectionState.STOPPING) return null
        val retry = snapshot.retryAttempt + 1
        snapshot = if (retry > maxRetries) snapshot.copy(state = GeminiConnectionState.FAILED)
        else snapshot.copy(state = GeminiConnectionState.RECONNECTING, retryAttempt = retry)
        return retry.takeIf { it <= maxRetries }
    }

    @Synchronized fun markStopping(): GeminiConnectionSnapshot {
        snapshot = snapshot.copy(state = GeminiConnectionState.STOPPING, generation = snapshot.generation + 1)
        return snapshot
    }

    @Synchronized fun markDisconnected() {
        snapshot = snapshot.copy(state = GeminiConnectionState.DISCONNECTED)
    }

    fun isCurrent(generation: Int): Boolean = generation == snapshot.generation
    fun exhausted(): Boolean = snapshot.state == GeminiConnectionState.FAILED

    fun backoffMs(retry: Int, jitterSeed: Int): Long {
        val base = 500L shl (retry - 1).coerceIn(0, 4)
        val jitter = ((jitterSeed.toLong() * 1103515245L + 12345L) ushr 16) % 251L
        return (base + jitter).coerceAtMost(8_250L)
    }

    private fun updateIfCurrent(
        generation: Int,
        transform: (GeminiConnectionSnapshot) -> GeminiConnectionSnapshot
    ): Boolean {
        if (generation != snapshot.generation || snapshot.state == GeminiConnectionState.STOPPING) return false
        snapshot = transform(snapshot)
        return true
    }
}
