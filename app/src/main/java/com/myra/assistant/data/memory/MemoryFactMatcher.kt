package com.myra.assistant.data.memory

import java.util.Locale

object MemoryFactMatcher {
    fun isSameActiveFact(existing: MemoryEntity?, candidate: MemoryCandidate): Boolean {
        if (existing == null || !existing.active) return false
        if (existing.stableKey.trim() != candidate.stableKey.trim()) return false
        return normalize(existing.fact) == normalize(candidate.fact)
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
