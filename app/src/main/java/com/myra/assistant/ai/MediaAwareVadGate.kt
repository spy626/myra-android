package com.myra.assistant.ai

/** Media energy is only a candidate. ASR text is required before it becomes user speech. */
internal class MediaAwareVadGate {
    enum class Result { POSSIBLE_MEDIA, CONFIRMED_USER, REJECTED_MEDIA, NONE }
    private var candidate = false
    private var confirmed = false
    var candidateId: Long = 0L; private set
    var startedAt: Long = 0L; private set
    var peakEnergy: Float = 0f; private set
    var transcriptEvidence: String = ""; private set

    @Synchronized fun onEnergyStarted(now: Long = System.nanoTime() / 1_000_000L, energy: Float = 0f): Result {
        candidate = true
        candidateId += 1
        startedAt = now
        peakEnergy = maxOf(peakEnergy, energy)
        transcriptEvidence = ""
        return Result.POSSIBLE_MEDIA
    }

    @Synchronized fun confirmFromTranscript(text: String = ""): Result {
        if (!candidate || confirmed) return Result.NONE
        transcriptEvidence = text.take(160)
        confirmed = true
        return Result.CONFIRMED_USER
    }

    @Synchronized fun onEnergyEnded(): Result {
        val result = if (confirmed) Result.CONFIRMED_USER else if (candidate) Result.REJECTED_MEDIA else Result.NONE
        candidate = false; confirmed = false; peakEnergy = 0f; transcriptEvidence = ""
        return result
    }

    @Synchronized fun reset() { candidate = false; confirmed = false; peakEnergy = 0f; transcriptEvidence = "" }
}
