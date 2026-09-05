package com.myra.assistant.service

/** Generation-scoped bookkeeping for the controlled natural-voice no-audio timeout. */
internal class ControlledSpeechTimeoutGate {
    private var activeToken: Long? = null
    private var acceptedAudio = false

    fun start(token: Long) {
        activeToken = token
        acceptedAudio = false
    }

    fun acceptFirstAudio(token: Long): Boolean {
        if (activeToken != token || acceptedAudio) return false
        acceptedAudio = true
        return true
    }

    fun shouldFire(token: Long): Boolean = activeToken == token && !acceptedAudio

    fun clear(token: Long) {
        if (activeToken == token) activeToken = null
    }
}
