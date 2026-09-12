package com.myra.assistant.ai

/** Conservative textual evidence that a media-time ASR candidate contains real language. */
internal object MediaSpeechCoherencePolicy {
    fun isCoherent(text: String): Boolean {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length < 6) return false
        val words = Regex("[\\p{L}\\p{N}']+").findAll(clean).map { it.value }.toList()
        if (words.size < 2) return false
        val letterCount = clean.count { it.isLetter() }
        if (letterCount < 5 || letterCount.toDouble() / clean.length < 0.55) return false
        if (words.map { it.lowercase() }.distinct().size == 1 && words.size < 4) return false
        return words.any { it.length >= 3 }
    }
}
