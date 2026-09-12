package com.myra.assistant.agent

data class ProactiveCandidate(val key: String, val relevance: Double, val usefulness: Double, val confidence: Double)

class ProactiveCompanionPolicy(
    private val cooldownMs: Long = 120_000L,
    private val threshold: Double = 0.78
) {
    private var lastSpokenAt = 0L
    private var lastKey = ""

    @Synchronized fun shouldSpeak(candidate: ProactiveCandidate, enabled: Boolean, now: Long): Boolean {
        if (!enabled || candidate.key.isBlank() || candidate.key == lastKey || now - lastSpokenAt < cooldownMs) return false
        val score = candidate.relevance * 0.35 + candidate.usefulness * 0.35 + candidate.confidence * 0.30
        if (score < threshold) return false
        lastKey = candidate.key
        lastSpokenAt = now
        return true
    }
}
