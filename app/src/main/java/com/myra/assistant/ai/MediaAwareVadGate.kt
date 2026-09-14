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
    var endedAt: Long = 0L; private set

    @Synchronized fun onEnergyStarted(now: Long = System.nanoTime() / 1_000_000L, energy: Float = 0f): Result {
        candidate = true
        candidateId += 1
        startedAt = now
        peakEnergy = maxOf(peakEnergy, energy)
        transcriptEvidence = ""
        endedAt = 0L
        return Result.POSSIBLE_MEDIA
    }

    @Synchronized fun confirmFromTranscript(text: String = "", now: Long = System.nanoTime() / 1_000_000L): Result {
        if (!candidate || confirmed) return Result.NONE
        if (endedAt > 0L && now - endedAt > ASR_CONFIRMATION_GRACE_MS) {
            clearCandidate()
            return Result.NONE
        }
        transcriptEvidence = text.take(160)
        confirmed = true
        return Result.CONFIRMED_USER
    }

    @Synchronized fun onEnergyEnded(now: Long = System.nanoTime() / 1_000_000L): Result {
        endedAt = now
        val result = if (confirmed) Result.CONFIRMED_USER else if (candidate) Result.REJECTED_MEDIA else Result.NONE
        if (confirmed) clearCandidate()
        return result
    }

    @Synchronized fun endedBeforeConfirmation(): Boolean = endedAt > 0L
    @Synchronized fun reset() = clearCandidate()

    private fun clearCandidate() {
        candidate = false; confirmed = false; peakEnergy = 0f; transcriptEvidence = ""; endedAt = 0L
    }

    private companion object { const val ASR_CONFIRMATION_GRACE_MS = 4_000L }
}
