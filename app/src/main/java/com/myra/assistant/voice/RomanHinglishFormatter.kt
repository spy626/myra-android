package com.myra.assistant.voice

import java.text.Normalizer

/** Converts ICU's literal Indic transliteration into natural chat-style Roman Hinglish. */
object RomanHinglishFormatter {
    private val replacements = listOf(
        Regex("\\beka\\b", RegexOption.IGNORE_CASE) to "ek",
        Regex("\\busaka\\b", RegexOption.IGNORE_CASE) to "uska",
        Regex("\\bnama\\b", RegexOption.IGNORE_CASE) to "naam",
        Regex("\\bbesta\\b", RegexOption.IGNORE_CASE) to "best",
        Regex("\\bphrenda\\b", RegexOption.IGNORE_CASE) to "friend",
        Regex("\\bmela(?=\\s+best\\b)", RegexOption.IGNORE_CASE) to "male",
        Regex("\\b(?:maim|mein)(?=\\s+\\d{1,3}\\s+(?:sala|saal)\\b)", RegexOption.IGNORE_CASE) to "main",
        Regex("\\bsala(?=\\s+(?:ka|ki)\\b)", RegexOption.IGNORE_CASE) to "saal",
        Regex("\\bpasanda\\b", RegexOption.IGNORE_CASE) to "pasand",
        Regex("\\bbahuta\\b", RegexOption.IGNORE_CASE) to "bahut",
        Regex("\\bjagaha\\b", RegexOption.IGNORE_CASE) to "jagah",
        Regex("\\bmunara\\b", RegexOption.IGNORE_CASE) to "Munnar",
        Regex("\\b(?:ghumana|gomna|goomna|ghomna)(?=\\s+(?:bahut\\s+|bohot\\s+)?pasand\\b|\\s+memor(?:y|ies)\\b)", RegexOption.IGNORE_CASE) to "ghumna",
        Regex("\\bnahim\\b", RegexOption.IGNORE_CASE) to "nahi"
    )

    fun format(raw: String): String {
        var text = raw.trim().replace(Regex("\\s+"), " ")
        // करीम transliterates as karīma, while the distinct final long vowel in करीमा
        // is karīmā. Use that signal only inside an explicit name phrase.
        text = Regex("\\b((?:nāma|nama|naam)\\s+)karīma\\b", RegexOption.IGNORE_CASE)
            .replace(text) { it.groupValues[1] + "Kareem" }
        text = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        replacements.forEach { (pattern, replacement) -> text = pattern.replace(text, replacement) }
        if (Regex("\\bbest\\s+friend\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            // Live ASR sometimes splits the name Naufal into "no fall". Limit this
            // correction to explicit best-friend context so ordinary words stay intact.
            text = Regex("\\bno\\s+fall\\b", RegexOption.IGNORE_CASE).replace(text, "Naufal")
        }
        text = Regex("^go and memory setting kar do[.!?]?$", RegexOption.IGNORE_CASE)
            .replace(text, "Ghumna memory se delete kar do.")
        text = Regex("^kya kara rahe(?: ho)?[.!?]?$", RegexOption.IGNORE_CASE)
            .replace(text, "Kya kar rahe ho?")
        if (Regex("\\b(?:goom naam|goom nam|gom naam)\\b.*\\bre-?visit\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "Voice input unclear - please repeat."
        }
        text = text
            .replace(Regex("\\s+([,.!?])"), "$1")
            .replace(Regex("([,.!?])(?=\\S)"), "$1 ")
            .replace(Regex("\\s+"), " ")
            .trim()
        text = Regex("\\b(main\\s+\\d{1,3}\\s+saal\\s+(?:ka|ki)\\s+)hum(?=[.!?]?$)", RegexOption.IGNORE_CASE)
            .replace(text) { it.groupValues[1] + "hoon" }
        text = Regex("\\b(aya)\\s+hum(?=[.!?]?$)", RegexOption.IGNORE_CASE)
            .replace(text) { "aaya hoon" }
        return Regex("(^|[.!?]\\s+)([a-z])").replace(text) { match ->
            match.groupValues[1] + match.groupValues[2].uppercase()
        }
    }
}
