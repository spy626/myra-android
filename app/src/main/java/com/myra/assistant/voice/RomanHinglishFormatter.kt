package com.myra.assistant.voice

import java.text.Normalizer

/** Converts ICU's literal Indic transliteration into natural chat-style Roman Hinglish. */
object RomanHinglishFormatter {
    private val replacements = listOf(
        Regex("\\b(?:ēka|eka)\\b", RegexOption.IGNORE_CASE) to "ek",
        Regex("\\b(?:usakā|usaka)\\b", RegexOption.IGNORE_CASE) to "uska",
        Regex("\\b(?:nāma|nama)\\b", RegexOption.IGNORE_CASE) to "naam",
        Regex("\\b(?:bēsṭa|besta)\\b", RegexOption.IGNORE_CASE) to "best",
        Regex("\\b(?:phrēṇḍa|phrenda)\\b", RegexOption.IGNORE_CASE) to "friend",
        Regex("\\b(?:mēla|mela)(?=\\s+best\\b)", RegexOption.IGNORE_CASE) to "male"
    )

    fun format(raw: String): String {
        var text = raw.trim().replace(Regex("\\s+"), " ")
        replacements.forEach { (pattern, replacement) -> text = pattern.replace(text, replacement) }
        // करीम transliterates as karīma, while the distinct final long vowel in करीमा
        // is karīmā. Use that signal only inside an explicit name phrase.
        text = Regex("\\b(naam\\s+)karīma\\b", RegexOption.IGNORE_CASE)
            .replace(text) { it.groupValues[1] + "Kareem" }
        text = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+([,.!?])"), "$1")
            .replace(Regex("([,.!?])(?=\\S)"), "$1 ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return Regex("(^|[.!?]\\s+)([a-z])").replace(text) { match ->
            match.groupValues[1] + match.groupValues[2].uppercase()
        }
    }
}
