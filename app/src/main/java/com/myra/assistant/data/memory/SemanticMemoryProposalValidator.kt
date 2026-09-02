package com.myra.assistant.data.memory

import java.util.Locale

/** Converts a Gemini proposal into a bounded candidate only when user speech supports it. */
object SemanticMemoryProposalValidator {
    private val safeKey = Regex("[a-z0-9][a-z0-9:_-]{1,49}")
    private val prohibited = Regex(
        """\b(?:otp|passwords?|passcode|pin|cvv|security code|verification code|recovery code|authentication token|auth token|api key|private key|seed phrase|bank|account number|card number|aadhaar|aadhar|pan number|passport number)\b""",
        RegexOption.IGNORE_CASE
    )
    private val sensitive = Regex(
        """\b(?:address|bank|account number|diagnosis|disease|medical|religion|sexual|trauma|fear|afraid)\b""",
        RegexOption.IGNORE_CASE
    )
    private val malformedFact = Regex(
        """\b(?:zopy|tum|user)\s+likes\s+to\b|\bre-?visit\s+travel\s+destinations?\b""",
        RegexOption.IGNORE_CASE
    )
    private val preferenceSignal = Regex(
        "\\b(?:like|likes|liked|love|loves|prefer|prefers|favorite|favourite|enjoy|enjoys|pasand)\\b",
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
            prohibited.containsMatchIn(cleanFact) || prohibited.containsMatchIn(cleanEvidence) ||
            malformedFact.containsMatchIn(cleanFact)
        ) return null

        val contextTokens = meaningfulTokens(conversationContext)
        val evidenceTokens = meaningfulTokens(cleanEvidence)
        val factTokens = meaningfulTokens(cleanFact)
        if (evidenceTokens.isEmpty() || contextTokens.isEmpty()) return null
        val evidenceGrounding = evidenceTokens.count(contextTokens::contains).toDouble() / evidenceTokens.size
        val factGrounding = factTokens.count(contextTokens::contains).toDouble() / factTokens.size.coerceAtLeast(1)
        if (evidenceGrounding < 0.70 || factGrounding < 0.40) return null
        // Entity overlap alone is not evidence of sentiment. For example, "visited
        // Munnar" must never become "likes Munnar" unless preference wording exists
        // in the user's actual conversation.
        if (preferenceSignal.containsMatchIn(cleanFact) &&
            !preferenceSignal.containsMatchIn(conversationContext)
        ) return null

        val sensitivity = when {
            sensitive.containsMatchIn(cleanFact) || sensitive.containsMatchIn(cleanEvidence) ->
                MemorySensitivity.SENSITIVE
            category in LOW_RISK_CATEGORIES -> MemorySensitivity.LOW
            else -> MemorySensitivity.PERSONAL
        }
        return MemoryRelationshipPolicy.canonicalize(MemoryCandidate(
            category = category,
            fact = cleanFact,
            stableKey = "semantic:${category.name.lowercase(Locale.ROOT)}:$key",
            sensitivity = sensitivity,
            confidence = confidence.coerceIn(0.0, 0.95),
            source = "gemini_grounded_conversation"
        ))
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

    private val LOW_RISK_CATEGORIES = setOf(
        MemoryCategory.PREFERENCE,
        MemoryCategory.COMMUNICATION_STYLE,
        MemoryCategory.WORKFLOW,
        MemoryCategory.APP_USAGE,
        MemoryCategory.SOLUTION
    )
}
