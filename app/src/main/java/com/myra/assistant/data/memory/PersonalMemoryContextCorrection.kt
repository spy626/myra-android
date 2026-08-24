package com.myra.assistant.data.memory

object PersonalMemoryContextCorrection {
    private val shortFriendReplacement = Regex(
        "^(?:nahi|nahin|nai|nhi|nehi|no|actually)[, ]+([\\p{L}][\\p{L} .'-]{1,39})$",
        RegexOption.IGNORE_CASE
    )

    fun resolve(raw: String, pending: MemoryCandidate): MemoryCandidate? {
        if (pending.stableKey != "person:best_friend") return null
        val clean = raw.trim().trimEnd('.', ',', '?', '!', '।').replace(Regex("\\s+"), " ")
        val name = shortFriendReplacement.matchEntire(clean)?.groupValues?.get(1)?.trim()
            ?: return null
        return PersonalMemoryExtractor.extract("$name meri best friend hai")
    }
}
