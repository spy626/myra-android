package com.myra.assistant.data.memory

import java.util.Locale

/** Keeps every fact about one person on the same corrected display name and key. */
object PersonLinkedMemoryIdentity {
    data class Rename(val stableKey: String, val fact: String)

    fun belongsTo(memory: MemoryEntity, names: Collection<String>): Boolean {
        if (memory.category != MemoryCategory.PERSON.name) return false
        val storedLinkedName = linkedFactPersonName(memory.fact)
        return names.any { name ->
            val canonical = BestFriendNameCanonicalizer.canonicalize(name)
            val storedFriend = MemoryRelationshipPolicy.personName(memory.fact)
            (storedFriend != null && sameName(storedFriend, canonical)) ||
                (storedLinkedName != null && sameName(storedLinkedName, canonical)) ||
                memory.stableKey.startsWith("person:${stableToken(canonical)}:") ||
                startsWithName(memory.fact, canonical)
        }
    }

    fun rename(memory: MemoryEntity, oldNames: Collection<String>, canonicalName: String): Rename? {
        if (!belongsTo(memory, oldNames)) return null
        // Replace the spelling actually stored in the row, not the merely similar
        // correction alias. This is the Naufara(row) vs Nauphara(command) failure.
        val storedName = linkedFactPersonName(memory.fact)
            ?.takeIf { actual -> oldNames.any { sameName(actual, it) } }
            ?: oldNames.firstOrNull { startsWithName(memory.fact, it) }
        val fact = if (storedName == null) memory.fact else memory.fact.replaceFirst(
            Regex("^${Regex.escape(storedName)}\\b", RegexOption.IGNORE_CASE),
            canonicalName
        )
        var key = memory.stableKey
        (oldNames + listOfNotNull(storedName)).forEach { old ->
            val prefix = "person:${stableToken(old)}:"
            if (key.startsWith(prefix)) key = "person:${stableToken(canonicalName)}:${key.removePrefix(prefix)}"
        }
        return Rename(key, fact)
    }

    private fun startsWithName(fact: String, name: String): Boolean =
        Regex("^${Regex.escape(name.trim())}\\b", RegexOption.IGNORE_CASE).containsMatchIn(fact)

    private fun sameName(left: String, right: String): Boolean =
        left.equals(right, ignoreCase = true) ||
            BestFriendNameCanonicalizer.canonicalize(left)
                .equals(BestFriendNameCanonicalizer.canonicalize(right), ignoreCase = true) ||
            BestFriendNameSimilarity.likelySame(left, right)

    private fun linkedFactPersonName(fact: String): String? =
        Regex("^(.+?)\\s+(?:has|creates)\\s+", RegexOption.IGNORE_CASE)
            .find(fact)?.groupValues?.get(1)?.trim()

    fun stableToken(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(36)
}
