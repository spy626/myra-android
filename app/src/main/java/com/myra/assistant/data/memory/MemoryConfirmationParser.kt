package com.myra.assistant.data.memory

import java.util.Locale

enum class MemoryConfirmationDecision { YES, NO, ADD }

object MemoryConfirmationParser {
    private val yes = Regex(
        "^(?:haan|ha|han|yes|yeah|yep|bilkul|theek\\s+hai|haan\\s+theek\\s+hai|" +
            "yaad\\s+rakho|save\\s+kar\\s+do|yes\\s+save\\s+kar\\s+do)$"
    )
    private val no = Regex(
        "^(?:nahi|nahin|na|no|nope|cancel|rehne\\s+do|" +
            "mat\\s+(?:save\\s+)?karo|save\\s+mat\\s+karo)$"
    )
    private val add = Regex(
        "^(?:dono|both|dono\s+(?:best\s+)?friends?|dono\s+(?:best\s+)?friend\s+hai|" +
            "dono\s+ko\s+(?:save|yaad)\s+(?:karo|rakho)|add\s+kar\s+do)$"
    )
    private val devanagariYes = setOf("हाँ", "हां", "हा", "जी हाँ", "जी हां", "ठीक है")
    private val devanagariNo = setOf("नहीं", "नही", "ना", "रहने दो", "सेव मत करो")

    fun parse(raw: String): MemoryConfirmationDecision? {
        val compact = raw.trim()
            .trimEnd('.', ',', '?', '!', '।')
            .trim()
            .replace(Regex("\\s+"), " ")
        if (compact in devanagariYes) return MemoryConfirmationDecision.YES
        if (compact in devanagariNo) return MemoryConfirmationDecision.NO

        val latin = compact.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        return when {
            add.matches(latin) -> MemoryConfirmationDecision.ADD
            yes.matches(latin) -> MemoryConfirmationDecision.YES
            no.matches(latin) -> MemoryConfirmationDecision.NO
            else -> null
        }
    }
}
