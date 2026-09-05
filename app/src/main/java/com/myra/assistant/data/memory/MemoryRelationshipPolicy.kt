package com.myra.assistant.data.memory

/** Keeps strong, unique relationship labels in one canonical memory slot. */
object MemoryRelationshipPolicy {
    const val BEST_FRIEND_KEY = "person:best_friend"

    private val bestFriendWords = Regex("\\bbest\\s+friend\\b", RegexOption.IGNORE_CASE)
    private val canonicalFact = Regex(
        "^(?:Zopy's|The user's) best friend is (.+)$",
        RegexOption.IGNORE_CASE
    )
    private val reverseFact = Regex(
        "^(.+?) is Zopy's (?:male |female )?best friend$",
        RegexOption.IGNORE_CASE
    )
    private val naturalFact = Regex(
        "^([\\p{L}][\\p{L} .'-]{1,39}) (?:meri|mere|my) (?:best|besta|besti) " +
            "(?:friend|friends|frend|frends|phrend|phrenda|dost) (?:hai|he|is)$",
        RegexOption.IGNORE_CASE
    )

    fun isBestFriend(candidate: MemoryCandidate): Boolean =
        candidate.stableKey.contains("best_friend", ignoreCase = true) ||
            bestFriendWords.containsMatchIn(candidate.fact)

    fun isBestFriend(memory: MemoryEntity): Boolean =
        memory.stableKey.contains("best_friend", ignoreCase = true) ||
            bestFriendWords.containsMatchIn(memory.fact)

    fun canonicalize(candidate: MemoryCandidate): MemoryCandidate {
        if (!isBestFriend(candidate)) return candidate
        val name = personName(candidate.fact)?.let(BestFriendNameCanonicalizer::canonicalize)
        return candidate.copy(
            stableKey = BEST_FRIEND_KEY,
            fact = name?.let { "Zopy's best friend is $it" } ?: candidate.fact
        )
    }

    /** Uses a person-specific slot when the user explicitly keeps multiple best friends. */
    fun canonicalizeAdditional(candidate: MemoryCandidate): MemoryCandidate {
        val canonical = canonicalize(candidate)
        val name = personName(canonical.fact) ?: return canonical
        val suffix = name.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
            .trim('_')
        return canonical.copy(stableKey = "$BEST_FRIEND_KEY:$suffix")
    }

    /** Best friends add by default; only an explicit confirmed replacement uses the shared slot. */
    fun canonicalizeForSave(candidate: MemoryCandidate, replaceExisting: Boolean): MemoryCandidate =
        if (isBestFriend(candidate) && !replaceExisting) canonicalizeAdditional(candidate)
        else canonicalize(candidate)

    fun personName(fact: String): String? {
        val clean = fact.trim().trimEnd('.')
        return canonicalFact.matchEntire(clean)?.groupValues?.get(1)?.trim()
            ?: reverseFact.matchEntire(clean)?.groupValues?.get(1)?.trim()
            ?: naturalFact.matchEntire(clean)?.groupValues?.get(1)?.trim()
    }
}
