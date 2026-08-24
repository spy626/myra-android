package com.myra.assistant.data.memory

import java.util.Locale

/**
 * Extracts only explicit, concrete, low-risk preferences from completed user turns.
 * Automatic memory must prefer missing a fact over saving a wrong inference.
 */
object AutomaticMemoryExtractor {
    private val prohibitedOrPersonal = Regex(
        """\b(?:otp|password|passcode|pin|cvv|security\s*code|verification\s*code|recovery\s*code|private\s*key|seed\s*phrase|bank|account|address|health|disease|diagnosis|religion|sexual|trauma|fear|afraid|friend|dost|girlfriend|boyfriend|wife|husband|mother|father|brother|sister|relationship|age|years?\s+old|saal)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ambiguousSubject = Regex(
        """^(?:it|this|that|these|those|ye|yeh|vo|woh|wo|isko|usko|ise|use|something|kuch)$""",
        RegexOption.IGNORE_CASE
    )
    private val negation = Regex(
        """\b(?:do\s+not|don't|dont|not|never|nahi|nahin|pasand\s+nahi)\b""",
        RegexOption.IGNORE_CASE
    )

    fun extract(raw: String): MemoryCandidate? {
        val text = raw.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        if (text.length !in 4..160 || negation.containsMatchIn(text) ||
            prohibitedOrPersonal.containsMatchIn(text)
        ) return null

        englishPreference(text)?.let { subject -> return preference(subject) }
        hinglishPreference(text)?.let { subject -> return preference(subject) }
        favoritePreference(text)?.let { (kind, value) ->
            val cleanKind = cleanSubject(kind) ?: return null
            val cleanValue = cleanSubject(value) ?: return null
            return MemoryCandidate(
                category = MemoryCategory.PREFERENCE,
                fact = "Zopy's favorite $cleanKind is $cleanValue",
                stableKey = "preference:favorite:${normalize(cleanKind)}",
                sensitivity = MemorySensitivity.LOW,
                confidence = 0.95,
                source = "automatic_conversation"
            )
        }
        return null
    }

    private fun englishPreference(text: String): String? =
        Regex(
            """^(?:i|i\s+really)\s+(?:like|love|prefer|enjoy)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.groupValues?.get(1)

    private fun hinglishPreference(text: String): String? =
        listOf(
            Regex(
                """^mujhe\s+(.+?)\s+(?:(?:bahut|bohot|bohat|kaafi)\s+)?pasand\s+(?:hai|hain|he)$""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """^main\s+(.+?)\s+(?:pasand\s+karta|pasand\s+karti)\s+(?:hun|hoon|hu)$""",
                RegexOption.IGNORE_CASE
            )
        ).firstNotNullOfOrNull { it.matchEntire(text)?.groupValues?.get(1) }

    private fun favoritePreference(text: String): Pair<String, String>? =
        Regex(
            """^(?:my|mera|meri)\s+(?:favorite|favourite|favret|pasandida)\s+([\p{L}\p{N} ]{2,30}?)\s+(?:is|hai|he)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.let { it.groupValues[1] to it.groupValues[2] }

    private fun preference(rawSubject: String): MemoryCandidate? {
        val subject = cleanSubject(rawSubject)?.let(::canonicalSubject) ?: return null
        return MemoryCandidate(
            category = MemoryCategory.PREFERENCE,
            fact = "Zopy likes $subject",
            stableKey = "preference:likes:${normalize(subject)}",
            sensitivity = MemorySensitivity.LOW,
            confidence = 0.93,
            source = "automatic_conversation"
        )
    }

    private fun cleanSubject(value: String): String? {
        val clean = value.trim().trim('"', '\'', '.', ',', '!', '?')
            .replace(Regex("\\s+"), " ")
        val words = clean.split(' ').filter(String::isNotBlank)
        if (clean.length !in 2..80 || words.size !in 1..12 ||
            ambiguousSubject.matches(clean) || prohibitedOrPersonal.containsMatchIn(clean)
        ) return null
        return clean
    }

    private fun canonicalSubject(value: String): String {
        val normalized = normalize(value)
        return when {
            Regex("(?:science|sainsa|sains)\\s+(?:science\\s+)?(?:fiction|phiksana|phiksan).*?(?:movie|muvi|muvija)").containsMatchIn(normalized) ->
                "science-fiction movies"
            Regex("(?:horror|horara).*?(?:movie|muvi|muvija)").containsMatchIn(normalized) ->
                "horror movies"
            else -> value
        }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
