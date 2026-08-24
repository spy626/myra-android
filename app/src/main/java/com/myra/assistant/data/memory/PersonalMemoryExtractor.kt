package com.myra.assistant.data.memory

import java.util.Locale

/**
 * Extracts only clear, durable personal facts from completed user turns.
 * Low-risk preferences are handled separately; secrets and highly sensitive
 * details are deliberately excluded from automatic learning.
 */
object PersonalMemoryExtractor {
    private const val MAX_INPUT_LENGTH = 220
    private val blocked = Regex(
        "\\b(?:otp|one time password|password|passcode|pin|cvv|bank|account number|card number|" +
            "address|aadhaar|aadhar|pan number|passport|diagnosis|disease|medical|health|" +
            "religion|caste|sexual|trauma|abuse|fear|phobia)\\b",
        RegexOption.IGNORE_CASE
    )

    private val agePatterns = listOf(
        Regex("^i am (\\d{1,3}) years? old$", RegexOption.IGNORE_CASE),
        Regex("^my age is (\\d{1,3})$", RegexOption.IGNORE_CASE),
        Regex("^meri age (?:is|hai) (\\d{1,3})$", RegexOption.IGNORE_CASE),
        Regex("^main (\\d{1,3}) saal (?:ka|ki) (?:hun|hoon|hu)$", RegexOption.IGNORE_CASE)
    )
    private val bestFriendPatterns = listOf(
        Regex("^my best friend is ([\\p{L}][\\p{L} .'-]{1,39})$", RegexOption.IGNORE_CASE),
        Regex("^([\\p{L}][\\p{L} .'-]{1,39}) meri best friend (?:hai|he)$", RegexOption.IGNORE_CASE),
        Regex("^meri best friend ([\\p{L}][\\p{L} .'-]{1,39}) (?:hai|he)$", RegexOption.IGNORE_CASE)
    )
    private val goalPatterns = listOf(
        Regex("^my (?:main )?goal is (.{3,100})$", RegexOption.IGNORE_CASE),
        Regex("^mera (?:main )?goal (.{3,100}) (?:hai|he)$", RegexOption.IGNORE_CASE)
    )
    private val projectPatterns = listOf(
        Regex("^i am (?:building|making|working on) (.{3,100})$", RegexOption.IGNORE_CASE),
        Regex("^main (.{3,100}) (?:bana|build|develop) (?:raha|kar raha) (?:hun|hoon|hu)$", RegexOption.IGNORE_CASE)
    )
    private val habitPatterns = listOf(
        Regex("^i (.{2,80}) every day$", RegexOption.IGNORE_CASE),
        Regex("^main roz (.{2,80}) (?:karta|karti) (?:hun|hoon|hu)$", RegexOption.IGNORE_CASE)
    )

    fun extract(raw: String): MemoryCandidate? {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.length !in 4..MAX_INPUT_LENGTH || blocked.containsMatchIn(text)) return null

        agePatterns.firstNotNullOfOrNull { it.matchEntire(text) }?.let { match ->
            val age = match.groupValues[1].toIntOrNull() ?: return null
            if (age !in 5..120) return null
            return candidate(MemoryCategory.IDENTITY, "Zopy is ${age} years old", "identity:age", 0.98)
        }

        bestFriendPatterns.firstNotNullOfOrNull { it.matchEntire(text) }?.let { match ->
            val name = cleanValue(match.groupValues[1]) ?: return null
            if (name.lowercase(Locale.ROOT) in ambiguousValues) return null
            return candidate(
                MemoryCategory.PERSON,
                "Zopy's best friend is ${name}",
                "person:best_friend",
                0.96
            )
        }

        goalPatterns.firstNotNullOfOrNull { it.matchEntire(text) }?.let { match ->
            val goal = cleanValue(match.groupValues[1]) ?: return null
            return candidate(MemoryCategory.GOAL, "Zopy's goal is ${goal}", "goal:primary", 0.93)
        }

        projectPatterns.firstNotNullOfOrNull { it.matchEntire(text) }?.let { match ->
            val project = cleanValue(match.groupValues[1]) ?: return null
            return candidate(MemoryCategory.PROJECT, "Zopy is working on ${project}", "project:primary", 0.92)
        }

        habitPatterns.firstNotNullOfOrNull { it.matchEntire(text) }?.let { match ->
            val habit = cleanValue(match.groupValues[1]) ?: return null
            if (habit.lowercase(Locale.ROOT) in ambiguousValues) return null
            return candidate(
                MemoryCategory.HABIT,
                "Zopy ${habit} every day",
                "habit:${stableToken(habit)}",
                0.90
            )
        }
        return null
    }

    private fun candidate(
        category: MemoryCategory,
        fact: String,
        stableKey: String,
        confidence: Double
    ) = MemoryCandidate(
        category = category,
        fact = fact,
        stableKey = stableKey,
        sensitivity = MemorySensitivity.PERSONAL,
        confidence = confidence
    )

    private fun cleanValue(value: String): String? {
        val clean = value.trim().trim('.', ',', '?', '!')
        if (clean.length !in 2..100 || blocked.containsMatchIn(clean)) return null
        return clean
    }

    private fun stableToken(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
        .trim('_')
        .take(48)

    private val ambiguousValues = setOf(
        "it", "this", "that", "he", "she", "they", "vo", "woh", "ye", "usko", "isko"
    )
}
