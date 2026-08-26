package com.myra.assistant.service

internal enum class ResponseOwner { MODEL, CONTROLLED_LOCAL }

/** Ensures one text/audio owner for a user turn. */
internal class TurnResponseArbiter {
    var turnId: Long = 0; private set
    var owner: ResponseOwner = ResponseOwner.MODEL; private set
    private var generationComplete = false
    private var playbackComplete = false

    fun begin(turnId: Long) {
        if (owner == ResponseOwner.CONTROLLED_LOCAL && !released()) return
        this.turnId = turnId
        owner = ResponseOwner.MODEL
        generationComplete = false
        playbackComplete = false
    }

    fun claimControlled(turnId: Long) {
        this.turnId = turnId
        owner = ResponseOwner.CONTROLLED_LOCAL
        generationComplete = false
        playbackComplete = false
    }

    fun acceptsOrdinaryModel(): Boolean = owner == ResponseOwner.MODEL
    fun controlledGenerationComplete() { if (owner == ResponseOwner.CONTROLLED_LOCAL) generationComplete = true }
    fun controlledPlaybackComplete() { if (owner == ResponseOwner.CONTROLLED_LOCAL) playbackComplete = true }
    fun released(): Boolean = owner != ResponseOwner.CONTROLLED_LOCAL || generationComplete && playbackComplete
    fun releaseIfComplete(): Boolean {
        if (!released()) return false
        owner = ResponseOwner.MODEL
        return true
    }
}
