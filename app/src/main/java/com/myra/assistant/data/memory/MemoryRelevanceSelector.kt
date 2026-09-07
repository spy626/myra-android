package com.myra.assistant.data.memory

import java.util.Locale

/** Selects a bounded relevant subset without sending every memory to Gemini. */
object MemoryRelevanceSelector {
    fun select(query: String, memories: List<MemoryEntity>, limit: Int = 5): List<MemoryEntity> {
        val boundedLimit = limit.coerceIn(1, 10)
        val recallable = memories.filter(::isNormallyRecallable)
        val queryTokens = tokens(query).filterNot(STOP_WORDS::contains).toSet()
        if (queryTokens.isEmpty()) return recallable
            .sortedWith(compareByDescending<MemoryEntity> { lifecycleRank(it.lifecycleStatus) }
                .thenByDescending { it.useCount }
                .thenByDescending { it.lastUsedAt }
                .thenByDescending { it.updatedAt })
            .take(boundedLimit)

        val contextualReference = CONTEXTUAL_REFERENCE.containsMatchIn(normalize(query))

        return recallable.asSequence()
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
                val categoryBoost = if (contextualReference) contextBoost(memory.category) else 0
                val usageBoost = memory.useCount.coerceAtMost(5) * 2
                val lifecycleBoost = when (memory.lifecycleStatus) {
                    MemoryLifecycleStatus.ACTIVE.name -> 4
                    MemoryLifecycleStatus.WEAKENING.name -> 0
                    else -> -20
                }
                val semanticScore = factOverlap * 10 + conceptualKeyOverlap * 4 + categoryBoost +
                    if (exactPhrase) 25 else 0
                // Frequency ranks relevant memories; it must never make an unrelated
                // memory relevant or resurrect an old person alias.
                val score = if (semanticScore > 0) semanticScore + usageBoost + lifecycleBoost else 0
                memory to score
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MemoryEntity, Int>> { it.second }
                .thenByDescending { it.first.lastUsedAt }
                .thenByDescending { it.first.updatedAt })
            .map { it.first }
            .take(boundedLimit)
            .toList()
    }

    private fun isNormallyRecallable(memory: MemoryEntity): Boolean =
        memory.active && memory.lifecycleStatus !in setOf(
            MemoryLifecycleStatus.HISTORICAL.name,
            MemoryLifecycleStatus.INACTIVE.name,
            MemoryLifecycleStatus.SUPERSEDED.name
        )

    private fun lifecycleRank(status: String): Int = when (status) {
        MemoryLifecycleStatus.ACTIVE.name -> 2
        MemoryLifecycleStatus.WEAKENING.name -> 1
        else -> 0
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

    private val CONTEXTUAL_REFERENCE = Regex(
        """\b(?:like before|normal settings|usually use|continue where|as usual|pehle jaisa|hamesha wala|normally)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun contextBoost(category: String): Int = when (category) {
        MemoryCategory.WORKFLOW.name,
        MemoryCategory.APP_USAGE.name,
        MemoryCategory.SOLUTION.name -> 12
        MemoryCategory.COMMUNICATION_STYLE.name -> 10
        MemoryCategory.PREFERENCE.name -> 6
        else -> 0
    }
}
