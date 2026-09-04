package com.myra.assistant.ai

import kotlin.math.abs
import kotlin.math.max

internal data class BargeInAssessment(
    val accepted: Boolean,
    val selfPlaybackProbability: Float,
    val playbackReferenceCorrelation: Float,
    val reason: String
)

/** Requires microphone energy materially independent from LYRA's playback reference. */
internal object SelfPlaybackBargeInGate {
    fun assess(micRms: Float, playbackReferenceRms: Float, sustained: Boolean, enabled: Boolean): BargeInAssessment {
        val reference = playbackReferenceRms.coerceAtLeast(0f)
        val correlation = if (reference <= 0.001f) 0f else
            (1f - abs(micRms - reference) / max(micRms, reference)).coerceIn(0f, 1f)
        val required = max(0.085f, reference * 1.65f)
        val independentNearMic = micRms >= required
        val selfProbability = if (reference <= 0.001f) 0f else
            ((correlation * 0.7f) + (if (independentNearMic) 0f else 0.3f)).coerceIn(0f, 1f)
        return when {
            !enabled -> BargeInAssessment(false, selfProbability, correlation, "barge_in_disabled")
            !sustained -> BargeInAssessment(false, selfProbability, correlation, "not_sustained")
            !independentNearMic -> BargeInAssessment(false, selfProbability, correlation, "probable_self_playback")
            else -> BargeInAssessment(true, selfProbability, correlation, "independent_near_mic_speech")
        }
    }
}
