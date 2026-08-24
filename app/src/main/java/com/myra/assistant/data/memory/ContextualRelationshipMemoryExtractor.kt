package com.myra.assistant.data.memory

/**
 * Combines at most a few completed, recent user turns. It intentionally requires both
 * an explicit best-friend relationship and an explicit name statement; vague mentions
 * such as "Kareem achha hai" never become a memory candidate.
 */
object ContextualRelationshipMemoryExtractor {
    private val relationship = Regex(
        "\\b(?:(?:mera|meri)\\s+(?:ek\\s+)?(?:male\\s+|female\\s+)?(?:best|besta|besti)\\s+(?:friend|frend|phrend|dost)|i\\s+have\\s+(?:a\\s+)?(?:male\\s+|female\\s+)?best\\s+friend)\\b",
        RegexOption.IGNORE_CASE
    )
    private val namePatterns = listOf(
        Regex("\\b(?:uska|unka)\\s+naam\\s+([\\p{L}][\\p{L}'-]{1,30})(?:\\s+hai|\\s+he)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:his|her|their)\\s+name\\s+is\\s+([\\p{L}][\\p{L}'-]{1,30})\\b", RegexOption.IGNORE_CASE),
        Regex("\\bmy\\s+best\\s+friend(?:'s)?\\s+name\\s+is\\s+([\\p{L}][\\p{L}'-]{1,30})\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:jiska|jinki)\\s+naam\\s+([\\p{L}][\\p{L}'-]{1,30})(?:\\s+hai|\\s+he)?\\b", RegexOption.IGNORE_CASE)
    )

    fun extract(completedTurns: List<String>): MemoryCandidate? {
        val cleanTurns = completedTurns.takeLast(3).map { it.trim() }.filter { it.isNotBlank() }
        if (cleanTurns.isEmpty()) return null
        val context = cleanTurns.joinToString(". ")
        if (!relationship.containsMatchIn(context)) return null
        val name = namePatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(context)?.groupValues?.getOrNull(1)?.trim()
        } ?: return null
        return PersonalMemoryExtractor.extract("$name meri best friend hai")
    }
}
