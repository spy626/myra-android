package com.myra.assistant.voice

import java.util.Locale

/** Rejects short noise-shaped ASR fragments without rewriting meaningful speech. */
object PhantomTranscriptFilter {
    private val knownNoise = setOf(
        "as", "in", "si", "sí", "hm", "hmm", "um", "uh", "ah", "oh", "mm", "ja"
    )
    private val repeatedCharacter = Regex("^([a-z])\\1+$", RegexOption.IGNORE_CASE)
    private val consonantNoise = Regex("^[bcdfghjklmnpqrstvwxyz]{1,8}$", RegexOption.IGNORE_CASE)

    fun shouldIgnore(raw: String): Boolean {
        val clean = raw.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isBlank()) return true
        return clean.split(' ').all { word ->
            word in knownNoise || repeatedCharacter.matches(word) || consonantNoise.matches(word)
        }
    }
}
