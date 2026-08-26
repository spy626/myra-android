package com.myra.assistant.service

internal enum class ModelAudioDecision {
    ACCEPT,
    BUFFER_UNTIL_SPEECH_END,
    DROP_STALE_GENERATION,
    DROP_CANCELLED_GENERATION,
    DROP_DUPLICATE_GENERATION
}

/**
 * Binds ordinary model audio to the generation that starts after a logical user turn.
 * Text equality and wall-clock delays are deliberately not used.
 */
internal class OrdinaryModelAudioGate {
    private var speechActive = false
    private var generationAtSpeechStart = 0L
    private var acceptedGeneration: Long? = null
    private val cancelledGenerations = mutableSetOf<Long>()

    fun onSpeechActivityStarted(latestGenerationId: Long): Long? {
        speechActive = true
        generationAtSpeechStart = latestGenerationId
        val cancelled = acceptedGeneration
        if (cancelled != null) cancelledGenerations += cancelled
        acceptedGeneration = null
        return cancelled
    }

    fun onSpeechActivityEnded() { speechActive = false }

    fun cancelGeneration(generationId: Long) {
        if (generationId > 0L) cancelledGenerations += generationId
        if (acceptedGeneration == generationId) acceptedGeneration = null
    }

    fun decide(generationId: Long): ModelAudioDecision {
        if (generationId in cancelledGenerations) return ModelAudioDecision.DROP_CANCELLED_GENERATION
        if (speechActive) {
            if (generationId <= generationAtSpeechStart) return ModelAudioDecision.DROP_STALE_GENERATION
            return ModelAudioDecision.BUFFER_UNTIL_SPEECH_END
        }
        val accepted = acceptedGeneration
        if (accepted == null) {
            acceptedGeneration = generationId
            return ModelAudioDecision.ACCEPT
        }
        return if (accepted == generationId) ModelAudioDecision.ACCEPT
        else ModelAudioDecision.DROP_DUPLICATE_GENERATION
    }

    fun isSpeechActive(): Boolean = speechActive
}
