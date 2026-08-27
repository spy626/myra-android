package com.myra.assistant.ai

/** Media energy is only a candidate. ASR text is required before it becomes user speech. */
internal class MediaAwareVadGate {
    enum class Result { POSSIBLE_MEDIA, CONFIRMED_USER, REJECTED_MEDIA, NONE }
    private var candidate = false
    private var confirmed = false

    @Synchronized fun onEnergyStarted(): Result {
        candidate = true
        return Result.POSSIBLE_MEDIA
    }

    @Synchronized fun confirmFromTranscript(): Result {
        if (!candidate || confirmed) return Result.NONE
        confirmed = true
        return Result.CONFIRMED_USER
    }

    @Synchronized fun onEnergyEnded(): Result {
        val result = if (confirmed) Result.CONFIRMED_USER else if (candidate) Result.REJECTED_MEDIA else Result.NONE
        candidate = false
        confirmed = false
        return result
    }

    @Synchronized fun reset() { candidate = false; confirmed = false }
}
