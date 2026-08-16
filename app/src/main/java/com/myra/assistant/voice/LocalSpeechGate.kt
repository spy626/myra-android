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

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private const val MINIMUM_PREFIX_WORDS = 2
}
