package com.myra.assistant.agent

data class ProactiveCandidate(val key: String, val relevance: Double, val usefulness: Double, val confidence: Double)


/**
 * Existing opt-in proactive policy gets an additional session gate.
 * No new microphone/screen capability is authorized here.
 */
data class ProactiveSessionGuard(
    val foreground: Boolean,
    val voiceSessionAllowed: Boolean,
    val taskBusy: Boolean,
    val awaitingUserReply: Boolean,
    val userOptedIn: Boolean,
)

class ProactiveCompanionPolicy(
    private val cooldownMs: Long = 120_000L,
    private val threshold: Double = 0.78
) {
    private var lastSpokenAt = 0L
    private var lastKey = ""

    @Synchronized fun shouldSpeakSafely(
        candidate: ProactiveCandidate,
        guard: ProactiveSessionGuard,
        now: Long,
    ): Boolean {
        if (!guard.userOptedIn || !guard.foreground || !guard.voiceSessionAllowed ||
            guard.taskBusy || guard.awaitingUserReply) return false
        return shouldSpeak(candidate, enabled = true, now = now)
    }

    @Synchronized fun shouldSpeak(candidate: ProactiveCandidate, enabled: Boolean, now: Long): Boolean {
        if (!enabled || candidate.key.isBlank() || candidate.key == lastKey || now - lastSpokenAt < cooldownMs) return false
        val score = candidate.relevance * 0.35 + candidate.usefulness * 0.35 + candidate.confidence * 0.30
        if (score < threshold) return false
        lastKey = candidate.key
        lastSpokenAt = now
        return true
    }
}
