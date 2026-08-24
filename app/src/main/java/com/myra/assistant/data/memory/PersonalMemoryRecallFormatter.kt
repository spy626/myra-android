package com.myra.assistant.data.memory

object PersonalMemoryRecallFormatter {
    fun format(facts: List<String>): String {
        val natural = facts.mapNotNull(::naturalFact).distinct()
        val sentence = when (natural.size) {
            0 -> "Abhi tumhare baare mein koi saved memory nahi hai."
            1 -> natural.single()
            else -> natural.dropLast(1).joinToString(", ") + ", aur " + natural.last()
        }
        return sentence.replaceFirstChar { it.uppercase() } + if (sentence.endsWith('.')) "" else "."
    }

    private fun naturalFact(fact: String): String? {
        val clean = fact.trim().trimEnd('.')
        if (clean.isBlank()) return null
        Regex("^Zopy is (\\d{1,3}) years old$", RegexOption.IGNORE_CASE)
            .matchEntire(clean)?.let { return "tum ${it.groupValues[1]} saal ke ho" }
        Regex("^Zopy's best friend is (.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(clean)?.let { return "${it.groupValues[1].trim()} tumhari best friend hai" }
        Regex("^Zopy's goal is (.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(clean)?.let { return "tumhara goal ${it.groupValues[1].trim()} hai" }
        Regex("^Zopy is working on (.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(clean)?.let { return "tum ${it.groupValues[1].trim()} par kaam kar rahe ho" }
        return clean.replace(Regex("^Zopy(?:'s)?\\s*", RegexOption.IGNORE_CASE), "Tumhara ")
    }
}
