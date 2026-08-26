package com.myra.assistant.data.memory

import java.util.Locale

/**
 * Extracts multiple durable facts about one explicitly named person from one turn.
 * Short-lived or exaggerated behaviour (for example, "bina soye game khelta hai")
 * is deliberately not converted into long-term memory.
 */
object PersonLinkedMemoryExtractor {
    private const val MAX_INPUT_LENGTH = 300
    private val blocked = Regex(
        "\\b(?:otp|password|passcode|pin|cvv|bank|account number|card number|aadhaar|aadhar|passport)\\b",
        RegexOption.IGNORE_CASE
    )
    private val bestFriendSignal = Regex(
        "\\b(?:best|besta)\\s+(?:friend|frend|phrend|phrenda)\\b",
        RegexOption.IGNORE_CASE
    )
    private val namedFriendPatterns = listOf(
        Regex(
            "(?:mera|meri|mari|mere)\\s+(?:ek\\s+)?(?:best|besta)\\s+(?:friend|frend|phrend|phrenda)\\s+(?:hai|he)\\s+([\\p{L}][\\p{L}'-]{1,29})",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "(?:mera|meri|mari|mere)\\s+(?:ek\\s+)?(?:best|besta)\\s+(?:friend|frend|phrend|phrenda)\\s+([\\p{L}][\\p{L}'-]{1,29})\\s+(?:hai|he)",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "([\\p{L}][\\p{L}'-]{1,29})\\s+(?:mera|meri|mere)\\s+(?:best|besta)\\s+(?:friend|frend|phrend|phrenda)\\s+(?:hai|he)",
            RegexOption.IGNORE_CASE
        ),
        Regex("my\\s+best\\s+friend\\s+is\\s+([\\p{L}][\\p{L}'-]{1,29})", RegexOption.IGNORE_CASE)
    )
    private val gamingChannelSignal = Regex(
        "\\b(?:gaming|gaminga|geming|geminga|game|गेमिंग)\\s+(?:youtube\\s+)?" +
            "(?:channel|chanel|cainal|cainala|canal|canala)\\b|" +
            "\\b(?:youtube\\s+)?(?:channel|chanel|cainal|cainala|canal|canala)" +
            "\\s+(?:hai|he|about\\s+gaming)\\b",
        RegexOption.IGNORE_CASE
    )

    fun extractAll(raw: String): List<MemoryCandidate> {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.length !in 8..MAX_INPUT_LENGTH || blocked.containsMatchIn(text) ||
            !bestFriendSignal.containsMatchIn(text)
        ) return emptyList()
        val observedName = namedFriendPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(text)?.groupValues?.get(1)
        } ?: return emptyList()
        val name = BestFriendNameCanonicalizer.canonicalize(observedName)
        if (name.lowercase(Locale.ROOT) in ambiguousNames) return emptyList()

        val facts = mutableListOf(bestFriend(name))
        if (gamingChannelSignal.containsMatchIn(text)) facts += gamingChannel(name)
        return facts
    }

    private fun bestFriend(name: String) = MemoryCandidate(
        category = MemoryCategory.PERSON,
        fact = "Zopy's best friend is $name",
        stableKey = MemoryRelationshipPolicy.BEST_FRIEND_KEY,
        sensitivity = MemorySensitivity.PERSONAL,
        confidence = 0.96,
        source = "automatic_person_facts"
    )

    private fun gamingChannel(name: String) = MemoryCandidate(
        category = MemoryCategory.PERSON,
        fact = "$name has a gaming channel",
        stableKey = "person:${stableToken(name)}:gaming_channel",
        sensitivity = MemorySensitivity.PERSONAL,
        confidence = 0.94,
        source = "automatic_person_facts"
    )

    private fun stableToken(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
        .trim('_')
        .take(36)

    private val ambiguousNames = setOf("he", "she", "they", "vo", "woh", "ye", "iska", "uska")
}
