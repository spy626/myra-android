package com.myra.assistant.service

internal enum class ModelAudioDecision { ACCEPT, DROP_STALE_GENERATION, DROP_DUPLICATE_GENERATION }

/**
 * Binds ordinary model audio to the generation that starts after a logical user turn.
 * Text equality and wall-clock delays are deliberately not used.
 */
internal class OrdinaryModelAudioGate {
    private var inputTurnActive = false
    private var generationAtInputStart = 0L
    private var acceptedGeneration: Long? = null

    fun onInputTurnStarted(latestGenerationId: Long) {
        inputTurnActive = true
        generationAtInputStart = latestGenerationId
        acceptedGeneration = null
    }

    fun decide(generationId: Long): ModelAudioDecision {
        if (!inputTurnActive) return ModelAudioDecision.ACCEPT
        if (generationId <= generationAtInputStart) return ModelAudioDecision.DROP_STALE_GENERATION
        val accepted = acceptedGeneration
        if (accepted == null) {
            acceptedGeneration = generationId
            return ModelAudioDecision.ACCEPT
        }
        return if (accepted == generationId) ModelAudioDecision.ACCEPT
        else ModelAudioDecision.DROP_DUPLICATE_GENERATION
    }

    fun onInputTurnCommitted() {
        inputTurnActive = false
    }
}
