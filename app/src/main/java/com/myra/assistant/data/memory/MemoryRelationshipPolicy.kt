package com.myra.assistant.data.memory

/** Keeps strong, unique relationship labels in one canonical memory slot. */
object MemoryRelationshipPolicy {
    const val BEST_FRIEND_KEY = "person:best_friend"

    private val bestFriendWords = Regex("\\bbest\\s+friend\\b", RegexOption.IGNORE_CASE)
    private val canonicalFact = Regex(
        "^Zopy's best friend is (.+)$",
        RegexOption.IGNORE_CASE
    )
    private val reverseFact = Regex(
        "^(.+?) is Zopy's (?:male |female )?best friend$",
        RegexOption.IGNORE_CASE
    )

    fun isBestFriend(candidate: MemoryCandidate): Boolean =
        candidate.stableKey.contains("best_friend", ignoreCase = true) ||
            bestFriendWords.containsMatchIn(candidate.fact)

    fun isBestFriend(memory: MemoryEntity): Boolean =
        memory.stableKey.contains("best_friend", ignoreCase = true) ||
            bestFriendWords.containsMatchIn(memory.fact)

    fun canonicalize(candidate: MemoryCandidate): MemoryCandidate =
        if (isBestFriend(candidate)) candidate.copy(stableKey = BEST_FRIEND_KEY) else candidate

    fun personName(fact: String): String? {
        val clean = fact.trim().trimEnd('.')
        return canonicalFact.matchEntire(clean)?.groupValues?.get(1)?.trim()
            ?: reverseFact.matchEntire(clean)?.groupValues?.get(1)?.trim()
    }
}
