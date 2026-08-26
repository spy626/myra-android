package com.myra.assistant.data.memory

import java.util.Locale

/** Keeps every fact about one person on the same corrected display name and key. */
object PersonLinkedMemoryIdentity {
    data class Rename(val stableKey: String, val fact: String)

    fun belongsTo(memory: MemoryEntity, names: Collection<String>): Boolean {
        if (memory.category != MemoryCategory.PERSON.name) return false
        return names.any { name ->
            val canonical = BestFriendNameCanonicalizer.canonicalize(name)
            val storedFriend = MemoryRelationshipPolicy.personName(memory.fact)
            (storedFriend != null && sameName(storedFriend, canonical)) ||
                memory.stableKey.startsWith("person:${stableToken(canonical)}:") ||
                startsWithName(memory.fact, canonical)
        }
    }

    fun rename(memory: MemoryEntity, oldNames: Collection<String>, canonicalName: String): Rename? {
        if (!belongsTo(memory, oldNames)) return null
        val oldName = oldNames.firstOrNull { startsWithName(memory.fact, it) }
        val fact = if (oldName == null) memory.fact else memory.fact.replaceFirst(
            Regex("^${Regex.escape(oldName)}\\b", RegexOption.IGNORE_CASE),
            canonicalName
        )
        var key = memory.stableKey
        oldNames.forEach { old ->
            val prefix = "person:${stableToken(old)}:"
            if (key.startsWith(prefix)) key = "person:${stableToken(canonicalName)}:${key.removePrefix(prefix)}"
        }
        return Rename(key, fact)
    }

    private fun startsWithName(fact: String, name: String): Boolean =
        Regex("^${Regex.escape(name.trim())}\\b", RegexOption.IGNORE_CASE).containsMatchIn(fact)

    private fun sameName(left: String, right: String): Boolean =
        left.equals(right, ignoreCase = true) || BestFriendNameSimilarity.likelySame(left, right)

    fun stableToken(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(36)
}
