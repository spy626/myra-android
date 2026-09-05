package com.myra.assistant.service

import java.util.concurrent.atomic.AtomicLong

internal enum class ResponseOwner { MODEL, CONTROLLED_LOCAL }
internal enum class ResponseLifecycle { IDLE, THINKING, GENERATING_VOICE, PLAYING, FINISHED }

/**
 * Single response owner for each turn. A newer turn/generation invalidates the old
 * response before its audio is allowed to become authoritative.
 */
internal class TurnResponseArbiter {
    var turnId: Long = 0; private set
    var owner: ResponseOwner = ResponseOwner.MODEL; private set
    var lifecycle: ResponseLifecycle = ResponseLifecycle.IDLE; private set
    private var generationComplete = false
    private var playbackComplete = false
    private val generationSequence = AtomicLong(0L)
    var generationId: Long = 0L; private set

    @Synchronized fun begin(turnId: Long) {
        // A controlled response owns only its exact turn. A genuinely newer user
        // turn must supersede it even if an interrupted audio callback never reported
        // generation/playback completion. Same-turn late packets remain blocked.
        if (owner == ResponseOwner.CONTROLLED_LOCAL && !released() && this.turnId == turnId) return
        this.turnId = turnId
        owner = ResponseOwner.MODEL
        lifecycle = ResponseLifecycle.THINKING
        generationComplete = false
        playbackComplete = false
        generationId = generationSequence.incrementAndGet()
    }

    @Synchronized fun claimControlled(turnId: Long) {
        this.turnId = turnId
        owner = ResponseOwner.CONTROLLED_LOCAL
        lifecycle = ResponseLifecycle.GENERATING_VOICE
        generationComplete = false
        playbackComplete = false
        generationId = generationSequence.incrementAndGet()
    }

    @Synchronized fun markVoicePlaying(turnId: Long, generationId: Long): Boolean {
        if (!isCurrent(turnId, generationId)) return false
        lifecycle = ResponseLifecycle.PLAYING
        return true
    }

    @Synchronized fun markGenerationComplete(turnId: Long, generationId: Long): Boolean {
        if (!isCurrent(turnId, generationId)) return false
        generationComplete = true
        if (lifecycle != ResponseLifecycle.PLAYING) lifecycle = ResponseLifecycle.FINISHED
        return true
    }

    @Synchronized fun markPlaybackComplete(turnId: Long, generationId: Long): Boolean {
        if (!isCurrent(turnId, generationId)) return false
        playbackComplete = true
        if (generationComplete) lifecycle = ResponseLifecycle.FINISHED
        return true
    }

    @Synchronized fun invalidateCurrent(): Long {
        val old = generationId
        generationId = generationSequence.incrementAndGet()
        lifecycle = ResponseLifecycle.FINISHED
        generationComplete = true
        playbackComplete = true
        owner = ResponseOwner.MODEL
        return old
    }

    fun acceptsOrdinaryModel(): Boolean = owner == ResponseOwner.MODEL

    @Synchronized fun supersedeForNewUserTurn(turnId: Long): Long {
        require(turnId > 0L)
        val oldGeneration = generationId
        this.turnId = turnId
        owner = ResponseOwner.MODEL
        lifecycle = ResponseLifecycle.THINKING
        generationComplete = false
        playbackComplete = false
        generationId = generationSequence.incrementAndGet()
        return oldGeneration
    }
    @Synchronized fun controlledGenerationComplete() {
        if (owner == ResponseOwner.CONTROLLED_LOCAL) {
            generationComplete = true
            if (playbackComplete) lifecycle = ResponseLifecycle.FINISHED
        }
    }
    @Synchronized fun controlledPlaybackComplete() {
        if (owner == ResponseOwner.CONTROLLED_LOCAL) {
            playbackComplete = true
            if (generationComplete) lifecycle = ResponseLifecycle.FINISHED
        }
    }
    fun released(): Boolean = owner != ResponseOwner.CONTROLLED_LOCAL || generationComplete && playbackComplete

    @Synchronized fun releaseIfComplete(): Boolean {
        if (!released()) return false
        owner = ResponseOwner.MODEL
        lifecycle = ResponseLifecycle.FINISHED
        return true
    }

    @Synchronized fun isCurrent(turnId: Long, generationId: Long): Boolean =
        this.turnId == turnId && this.generationId == generationId && lifecycle != ResponseLifecycle.FINISHED
}
