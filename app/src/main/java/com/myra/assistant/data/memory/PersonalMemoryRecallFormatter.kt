package com.myra.assistant.data.memory

object PersonalMemoryRecallFormatter {
    fun format(facts: List<String>): String {
        val friendNames = facts.asSequence()
            .mapNotNull(::bestFriendName)
            .distinctBy { it.lowercase() }
            .toList()
        val natural = facts.asSequence()
            .filter { bestFriendName(it) == null }
            .mapNotNull(::naturalFact)
            .distinct()
            .toMutableList()
        if (friendNames.isNotEmpty()) {
            natural.add(
                0,
                if (friendNames.size == 1) "${friendNames.single()} tumhari best friend hai"
                else "${joinNaturally(friendNames)} tumhari best friends hain"
            )
        }
        val sentence = when (natural.size) {
            0 -> "Abhi tumhare baare mein koi saved memory nahi hai."
            1 -> natural.single()
            else -> natural.dropLast(1).joinToString(", ") + ", aur " + natural.last()
        }
        return sentence.replaceFirstChar { it.uppercase() } + if (sentence.endsWith('.')) "" else "."
    }

    private fun joinNaturally(values: List<String>): String = when (values.size) {
        0 -> ""
        1 -> values.single()
        2 -> "${values[0]} aur ${values[1]}"
        else -> values.dropLast(1).joinToString(", ") + " aur " + values.last()
    }

    private fun bestFriendName(fact: String): String? {
        val clean = fact.trim().trimEnd('.')
        return Regex("^(?:Zopy's|The user's) best friend is (.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(clean)?.groupValues?.get(1)?.trim()
            ?: Regex("^(.+?) is Zopy's (?:male |female )?best friend$", RegexOption.IGNORE_CASE)
                .matchEntire(clean)?.groupValues?.get(1)?.trim()
    }

    private fun naturalFact(fact: String): String? {
        val clean = fact.trim().trimEnd('.')
        if (clean.isBlank()) return null
        Regex("^Zopy is (\\d{1,3}) years old$", RegexOption.IGNORE_CASE)
            .matchEntire(clean)?.let { return "tum ${it.groupValues[1]} saal ke ho" }
        Regex("^Zopy's goal is (.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(clean)?.let { return "tumhara goal ${it.groupValues[1].trim()} hai" }
        Regex("^Zopy is working on (.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(clean)?.let { return "tum ${it.groupValues[1].trim()} par kaam kar rahe ho" }
        Regex("^Zopy likes (.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(clean)?.let {
                val subject = it.groupValues[1].trim()
                if (subject.startsWith("na ", ignoreCase = true)) return null
                return "tumhe $subject pasand hain"
            }
        Regex("^I (?:like|love|prefer) (.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(clean)?.let { return "tumhe ${it.groupValues[1].trim()} pasand hain" }
        return clean.replace(Regex("^Zopy(?:'s)?\\s*", RegexOption.IGNORE_CASE), "Tumhara ")
    }
}
