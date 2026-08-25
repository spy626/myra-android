package com.myra.assistant.data.memory

import java.util.Locale

/** Converts a Gemini proposal into a bounded candidate only when user speech supports it. */
object SemanticMemoryProposalValidator {
    private val safeKey = Regex("[a-z0-9][a-z0-9:_-]{1,49}")
    private val prohibited = Regex(
        """\b(?:otp|password|passcode|pin|cvv|security code|verification code|recovery code|private key|seed phrase|bank|account number|card number|aadhaar|aadhar|pan number|passport number)\b""",
        RegexOption.IGNORE_CASE
    )
    private val sensitive = Regex(
        """\b(?:address|bank|account number|diagnosis|disease|medical|religion|sexual|trauma|fear|afraid)\b""",
        RegexOption.IGNORE_CASE
    )
    private val stopWords = setOf(
        "a", "an", "the", "is", "are", "am", "was", "were", "to", "of", "and", "or",
        "my", "meri", "mera", "mere", "hai", "hain", "he", "hoon", "hun", "ka", "ki", "ke",
        "zopy", "zopy's", "user", "that", "this", "woh", "vo", "uska", "iska"
    )

    fun validate(
        fact: String,
        categoryName: String,
        memoryKey: String,
        evidence: String,
        confidence: Double,
        conversationContext: String
    ): MemoryCandidate? {
        val cleanFact = fact.trim().replace(Regex("\\s+"), " ")
        val cleanEvidence = evidence.trim().replace(Regex("\\s+"), " ")
        val category = runCatching {
            MemoryCategory.valueOf(categoryName.trim().uppercase(Locale.ROOT))
        }.getOrNull() ?: return null
        val key = normalize(memoryKey).replace(' ', '_')
        if (cleanFact.length !in 5..180 || cleanEvidence.length !in 3..180 ||
            !safeKey.matches(key) || confidence < 0.78 ||
            prohibited.containsMatchIn(cleanFact) || prohibited.containsMatchIn(cleanEvidence)
        ) return null

        val contextTokens = meaningfulTokens(conversationContext)
        val evidenceTokens = meaningfulTokens(cleanEvidence)
        val factTokens = meaningfulTokens(cleanFact)
        if (evidenceTokens.isEmpty() || contextTokens.isEmpty()) return null
        val evidenceGrounding = evidenceTokens.count(contextTokens::contains).toDouble() / evidenceTokens.size
        val factGrounding = factTokens.count(contextTokens::contains).toDouble() / factTokens.size.coerceAtLeast(1)
        if (evidenceGrounding < 0.70 || factGrounding < 0.40) return null

        val sensitivity = when {
            sensitive.containsMatchIn(cleanFact) || sensitive.containsMatchIn(cleanEvidence) ->
                MemorySensitivity.SENSITIVE
            category == MemoryCategory.PREFERENCE -> MemorySensitivity.LOW
            else -> MemorySensitivity.PERSONAL
        }
        return MemoryCandidate(
            category = category,
            fact = cleanFact,
            stableKey = "semantic:${category.name.lowercase(Locale.ROOT)}:$key",
            sensitivity = sensitivity,
            confidence = confidence.coerceIn(0.0, 0.95),
            source = "gemini_grounded_conversation"
        )
    }

    private fun meaningfulTokens(value: String): Set<String> = normalize(value)
        .split(' ')
        .filter { it.length >= 2 && it !in stopWords }
        .map {
            when {
                it in setOf("pasand", "pasanda", "likes", "liked") -> "like"
                it.endsWith('s') && it.length > 4 -> it.dropLast(1)
                else -> it
            }
        }
        .toSet()

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}:_-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
