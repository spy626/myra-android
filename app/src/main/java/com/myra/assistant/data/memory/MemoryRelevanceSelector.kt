package com.myra.assistant.data.memory

import java.util.Locale

/** Selects a bounded relevant subset without sending every memory to Gemini. */
object MemoryRelevanceSelector {
    fun select(query: String, memories: List<MemoryEntity>, limit: Int = 5): List<MemoryEntity> {
        val boundedLimit = limit.coerceIn(1, 10)
        val queryTokens = tokens(query).filterNot(STOP_WORDS::contains).toSet()
        if (queryTokens.isEmpty()) return memories
            .filter(MemoryEntity::active)
            .sortedByDescending(MemoryEntity::updatedAt)
            .take(boundedLimit)

        return memories.asSequence()
            .filter(MemoryEntity::active)
            .map { memory ->
                val factTokens = tokens(memory.fact).toSet()
                val keyTokens = tokens(memory.stableKey).toSet()
                val factOverlap = queryTokens.intersect(factTokens).size
                // Stable keys are useful for conceptual slots such as response_style,
                // but person aliases in a key must never resurrect an old identity.
                val conceptualKeyOverlap = queryTokens.intersect(keyTokens)
                    .count(CONCEPT_TOKENS::contains)
                val exactPhrase = normalize(memory.fact).contains(normalize(query)) ||
                    normalize(query).contains(normalize(memory.fact))
                val score = factOverlap * 10 + conceptualKeyOverlap * 4 +
                    if (exactPhrase) 25 else 0
                memory to score
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MemoryEntity, Int>> { it.second }
                .thenByDescending { it.first.updatedAt })
            .map { it.first }
            .take(boundedLimit)
            .toList()
    }

    private fun tokens(value: String): List<String> = normalize(value)
        .split(' ').filter { it.length >= 2 }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private val STOP_WORDS = setOf(
        "what", "which", "do", "does", "is", "are", "my", "mera", "meri", "mere",
        "kya", "hai", "he", "prefer", "about", "mein", "me"
    )

    private val CONCEPT_TOKENS = setOf(
        "preference", "response", "style", "project", "goal", "workflow", "language"
    )
}
