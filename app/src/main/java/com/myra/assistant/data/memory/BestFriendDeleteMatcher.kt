package com.myra.assistant.data.memory

/** Returns every active row for one canonical person, so duplicate rows cannot survive deletion. */
object BestFriendDeleteMatcher {
    fun findAll(query: String, memories: List<MemoryEntity>): List<MemoryEntity> {
        val canonicalQuery = BestFriendNameCanonicalizer.canonicalize(query)
        val canonicalMatches = memories.filter(MemoryRelationshipPolicy::isBestFriend).filter { memory ->
            val storedName = MemoryRelationshipPolicy.personName(memory.fact)
                ?.let(BestFriendNameCanonicalizer::canonicalize)
                ?: return@filter false
            storedName.equals(canonicalQuery, ignoreCase = true) ||
                BestFriendNameSimilarity.likelySame(storedName, canonicalQuery)
        }
        if (canonicalMatches.isNotEmpty()) return canonicalMatches
        return listOfNotNull(MemoryForgetMatcher.find(canonicalQuery, memories))
    }
}
