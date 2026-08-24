package com.myra.assistant.data.memory

import java.util.Locale

sealed class MemoryCommand {
    data class Remember(val candidate: MemoryCandidate, val displayFact: String) : MemoryCommand()
    data class Read(val query: String = "") : MemoryCommand()
    data class Forget(val query: String) : MemoryCommand()
}

object MemoryCommandParser {
    private val remember = Regex(
        "^(?:(?:lyra|laira)\\s+)?(?:(?:please|just)\\s+)*(?:remember|yaad\\s+rakhna|yaad\\s+ra(?:kh|k)?o|yaad\\s+rakh\\s+lo)(?:\\s+(?:that|ki))?\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val forget = Regex(
        "^(?:(?:please|just)\\s+)*(?:forget|bhool\\s+jao|bhoolna)(?:\\s+(?:that|ki))?\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val read = Regex(
        "^(?:(?:what)(?:\\s+(?:all))?(?:\\s+do)?(?:\\s+you)?\\s+remember(?:\\s+about\\s+me)?|(?:tumhe|tumhen|tumhem|tumko)\\s+mere\\s+(?:baare|bare)\\s+(?:mein|me|mem)\\s+kya\\s+(?:yaad|yada)\\s+(?:hai|he)|(?:abhi\\s+)?mere\\s+(?:baare|bare)\\s+(?:mein|me|mem)\\s+(?:tum\\s+)?kya\\s+(?:(?:pata|yaad|yada)\\s+(?:hai|he)|(?:jante|jaante|janate|janti|jaanti|janati)\\s+ho))[?]?$",
        RegexOption.IGNORE_CASE
    )

    fun looksLikeIntent(raw: String): Boolean = Regex(
        "^(?:(?:lyra|laira)\\s+)?(?:(?:please|just)\\s+)*(?:remember|forget|yaad\\s+rakhna|yaad\\s+ra(?:kh|k)?o|yaad\\s+rakh\\s+lo|bhool\\s+jao|bhoolna)\\b|^what(?:\\s+all)?(?:\\s+do)?(?:\\s+you)?\\s+remember\\b|^(?:tumhe|tumhen|tumhem|tumko)\\s+mere\\s+(?:baare|bare)|^(?:abhi\\s+)?mere\\s+(?:baare|bare)\\s+(?:mein|me|mem)\\s+(?:tum\\s+)?kya\\s+(?:pata|yaad|yada|jante|jaante|janate)",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(raw.trim())

    fun parse(raw: String): MemoryCommand? {
        val text = raw.trim().trimEnd('.', '?', '!')
        read.matchEntire(text)?.let { return MemoryCommand.Read() }
        remember.matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 2 }?.let { rawFact ->
            val fact = rawFact.replace(Regex("^(?:this|ye|yeh)\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (fact.length < 2) return null
            val category = classify(fact)
            val normalized = normalize(fact)
            return MemoryCommand.Remember(
                MemoryCandidate(category, fact, stableKey(category, normalized), sensitivity(fact, category), 1.0, true, "explicit_user_request"),
                fact
            )
        }
        forget.matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 2 }?.let {
            return MemoryCommand.Forget(normalize(it))
        }
        return null
    }

    private fun classify(fact: String): MemoryCategory {
        val text = normalize(fact)
        return when {
            Regex("\\b(?:age|years?\\s+old|saal|birthday|born)\\b").containsMatchIn(text) -> MemoryCategory.IDENTITY
            Regex("\\b(?:friend|mother|father|brother|sister|wife|husband|girlfriend|boyfriend|relationship|dost)\\b").containsMatchIn(text) -> MemoryCategory.PERSON
            Regex("\\b(?:every\\s+day|daily|usually|habit|roz|har\\s+din)\\b").containsMatchIn(text) -> MemoryCategory.HABIT
            Regex("\\b(?:goal|want\\s+to|plan\\s+to|become|sapna)\\b").containsMatchIn(text) -> MemoryCategory.GOAL
            Regex("\\b(?:project|app|website|business)\\b").containsMatchIn(text) -> MemoryCategory.PROJECT
            Regex("\\b(?:like|love|favorite|favourite|prefer|pasand)\\b").containsMatchIn(text) -> MemoryCategory.PREFERENCE
            else -> MemoryCategory.LIFE_EVENT
        }
    }

    private fun sensitivity(fact: String, category: MemoryCategory): MemorySensitivity {
        val text = normalize(fact)
        if (Regex("\\b(?:otp|password|passcode|pin|cvv|recovery\\s+code|seed\\s+phrase|private\\s+key)\\b").containsMatchIn(text)) return MemorySensitivity.PROHIBITED
        if (Regex("\\b(?:health|disease|diagnosis|religion|sexual|finance|bank|trauma|fear|afraid|exact\\s+address)\\b").containsMatchIn(text)) return MemorySensitivity.SENSITIVE
        return if (category in setOf(MemoryCategory.PERSON, MemoryCategory.HABIT, MemoryCategory.LIFE_EVENT, MemoryCategory.IDENTITY)) MemorySensitivity.PERSONAL else MemorySensitivity.LOW
    }

    private fun stableKey(category: MemoryCategory, normalized: String): String {
        if (category == MemoryCategory.IDENTITY && Regex("\\b(?:age|years?\\s+old|saal)\\b").containsMatchIn(normalized)) return "identity:age"
        return category.name.lowercase(Locale.ROOT) + ":" + normalized
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
