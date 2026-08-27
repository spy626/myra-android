package com.myra.assistant.screen

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ScreenFrame(
    val sessionId: String,
    val frameId: Long,
    val capturedAt: Long,
    val encodedAt: Long,
    val hash: String,
    val bytes: ByteArray,
    val source: String
)

data class ScreenQuery(
    val sessionId: String,
    val queryId: String,
    val userTurnId: Long,
    val requestedAt: Long
)

sealed class FreshFrameResult {
    data class Ready(val query: ScreenQuery, val frame: ScreenFrame) : FreshFrameResult()
    data class Unavailable(val query: ScreenQuery, val reason: String) : FreshFrameResult()
}

/**
 * The single authority for projection/session/frame identity. A ByteArray alone was
 * previously shared between periodic transport and question handling, so those paths
 * could disagree and late results had no stopped-session identity to reject.
 */
class ScreenVisionSession {
    private val frameSequence = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, ScreenQuery>()
    @Volatile var sessionId: String = ""
        private set
    @Volatile var state: ScreenShareState = ScreenShareState.IDLE
        private set
    @Volatile var latestFrame: ScreenFrame? = null
        private set

    @Synchronized fun start(): String {
        sessionId = UUID.randomUUID().toString()
        frameSequence.set(0)
        latestFrame = null
        pending.clear()
        state = ScreenShareState.REQUESTING_PERMISSION
        return sessionId
    }

    @Synchronized fun setState(next: ScreenShareState) { state = next }

    @Synchronized fun publish(bytes: ByteArray, capturedAt: Long, encodedAt: Long = capturedAt, source: String): ScreenFrame? {
        if (state != ScreenShareState.ACTIVE || sessionId.isBlank()) return null
        val frame = ScreenFrame(
            sessionId, frameSequence.incrementAndGet(), capturedAt, encodedAt,
            MessageDigest.getInstance("SHA-256").digest(bytes).take(8)
                .joinToString("") { "%02x".format(it) },
            bytes.copyOf(), source
        )
        latestFrame = frame
        return frame
    }

    fun createQuery(userTurnId: Long, now: Long): ScreenQuery? {
        if (state != ScreenShareState.ACTIVE || sessionId.isBlank()) return null
        val query = ScreenQuery(sessionId, UUID.randomUUID().toString(), userTurnId, now)
        pending[query.queryId] = query
        return query
    }

    fun complete(queryId: String, frame: ScreenFrame): FreshFrameResult? {
        val query = pending.remove(queryId) ?: return null
        return if (state == ScreenShareState.ACTIVE && query.sessionId == sessionId && frame.sessionId == sessionId) {
            FreshFrameResult.Ready(query, frame)
        } else FreshFrameResult.Unavailable(query, "stale_screen_session")
    }

    fun cancel(queryId: String, reason: String): FreshFrameResult.Unavailable? =
        pending.remove(queryId)?.let { FreshFrameResult.Unavailable(it, reason) }

    fun isCurrent(session: String): Boolean =
        state == ScreenShareState.ACTIVE && session.isNotBlank() && session == sessionId

    @Synchronized fun invalidate(finalState: ScreenShareState): List<ScreenQuery> {
        val cancelled = pending.values.toList()
        pending.clear()
        latestFrame = null
        state = finalState
        sessionId = ""
        return cancelled
    }
}
