package com.myra.assistant.voice

import java.util.Locale

object LocalSpeechGate {
    fun shouldReleaseBeforeTurnComplete(
        bufferUntilTurnComplete: Boolean,
        actual: String,
        expected: String
    ): Boolean = !bufferUntilTurnComplete && matchesExpectedPrefix(actual, expected)

    fun matchesExpectedPrefix(actual: String, expected: String): Boolean {
        val heard = normalize(actual)
        val prepared = normalize(expected)
        if (heard.isBlank() || prepared.isBlank()) return false
        if (heard == "ok" || heard == "okay") return false
        if (heard.split(' ').size < MINIMUM_PREFIX_WORDS) return false
        return prepared == heard || prepared.startsWith("$heard ")
    }

    fun matchesExpectedExactly(actual: String, expected: String): Boolean =
        normalize(actual).isNotBlank() && (
            normalize(actual) == normalize(expected) || semanticallyEquivalent(actual, expected)
        )

    /** Inputs are conservative Roman renderings produced by the existing formatter. */
    fun semanticallyEquivalent(actualRoman: String, expectedRoman: String): Boolean {
        val actual = normalize(actualRoman)
        val expected = normalize(expectedRoman)
        if (actual.isBlank() || expected.isBlank()) return false
        if (actual == expected) return true
        val actualWords = actual.split(' ').filter(String::isNotBlank)
        val expectedWords = expected.split(' ').filter(String::isNotBlank)
        if (minOf(actualWords.size, expectedWords.size) < 3) return false
        if (kotlin.math.abs(actualWords.size - expectedWords.size) > 2) return false
        val longest = maxOf(actual.length, expected.length)
        return 1.0 - editDistance(actual, expected).toDouble() / longest >= 0.76
    }

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

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1); current[0] = i + 1
            for (j in b.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = current
        }
        return previous[b.length]
    }

    private const val MINIMUM_PREFIX_WORDS = 2
    private const val PCM_24K_MONO_BYTES_PER_MS = 48
    private const val ESTIMATED_MS_PER_WORD = 180
    private const val MINIMUM_AUDIO_MS = 600
    private const val MAXIMUM_REQUIRED_AUDIO_MS = 1_800
}
