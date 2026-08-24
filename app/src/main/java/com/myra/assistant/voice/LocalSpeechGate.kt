package com.myra.assistant.voice

import java.util.Locale

object LocalSpeechGate {
    fun matchesExpectedPrefix(actual: String, expected: String): Boolean {
        val heard = normalize(actual)
        val prepared = normalize(expected)
        if (heard.isBlank() || prepared.isBlank()) return false
        if (heard == "ok" || heard == "okay") return false
        if (heard.split(' ').size < MINIMUM_PREFIX_WORDS) return false
        return prepared == heard || prepared.startsWith("$heard ")
    }

    fun matchesExpectedExactly(actual: String, expected: String): Boolean =
        normalize(actual).isNotBlank() && normalize(actual) == normalize(expected)

    fun hasEnoughBufferedNaturalAudio(audioBytes: Int, expected: String): Boolean {
        if (audioBytes <= 0) return false
        val wordCount = normalize(expected).split(' ').count { it.isNotBlank() }
        if (wordCount == 0) return false
        val minimumDurationMs = (wordCount * ESTIMATED_MS_PER_WORD)
            .coerceIn(MINIMUM_AUDIO_MS, MAXIMUM_REQUIRED_AUDIO_MS)
        return audioBytes >= minimumDurationMs * PCM_24K_MONO_BYTES_PER_MS
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private const val MINIMUM_PREFIX_WORDS = 2
    private const val PCM_24K_MONO_BYTES_PER_MS = 48
    private const val ESTIMATED_MS_PER_WORD = 180
    private const val MINIMUM_AUDIO_MS = 600
    private const val MAXIMUM_REQUIRED_AUDIO_MS = 1_800
}
