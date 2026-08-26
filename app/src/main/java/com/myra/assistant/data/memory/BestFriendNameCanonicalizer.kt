package com.myra.assistant.data.memory

import java.util.Locale

/** Canonicalizes only observed, high-confidence ASR spellings of known names. */
object BestFriendNameCanonicalizer {
    private val naufal = setOf(
        "naufal", "noufal", "nauphal", "nauphala", "nau phala",
        "now pal", "nowpal", "no fall", "now fall", "noval", "naipal", "naupal"
    )
    private val kareem = setOf("kareem", "karim")
    private val ayesha = setOf("ayesha", "ayeshaa", "aisha", "aysha")

    fun canonicalize(raw: String): String {
        val clean = raw.trim().trim('.', ',', '?', '!', '\'', '"')
            .replace(Regex("\\s+"), " ")
        val normalized = clean.lowercase(Locale.ROOT)
        return when (normalized) {
            in naufal -> "Naufal"
            in kareem -> "Kareem"
            in ayesha -> "Ayesha"
            else -> clean.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar().toString() else it.toString() }
        }
    }
}
