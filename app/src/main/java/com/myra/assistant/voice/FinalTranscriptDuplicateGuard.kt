package com.myra.assistant.voice

import java.util.Locale

/** Removes only an adjacent duplicated whole utterance from one FINAL turn. */
object FinalTranscriptDuplicateGuard {
    data class Result(val text: String, val duplicateDetected: Boolean, val collapseApplied: Boolean, val reason: String?)

    fun collapse(value: String): Result {
        val original = value.trim()
        if (original.isBlank()) return Result(original, false, false, null)
        val clauses = Regex("[^.!?।]+[.!?।]?").findAll(original)
            .map { it.value.trim() }.filter { it.isNotBlank() }.toList()
        if (clauses.size == 2 && isDuplicate(clauses[0], clauses[1])) {
            return Result(clauses[0], true, true, "adjacent_complete_clause")
        }
        val start = (original.length / 2 - 2).coerceAtLeast(1)
        val end = (original.length / 2 + 2).coerceAtMost(original.lastIndex)
        for (split in start..end) {
            val first = original.substring(0, split).trim()
            val second = original.substring(split).trim()
            if (isDuplicate(first, second)) return Result(first, true, true, "adjacent_complete_utterance")
        }
        return Result(original, false, false, null)
    }

    private fun isDuplicate(first: String, second: String): Boolean {
        val a = normalize(first); val b = normalize(second)
        if (a.split(' ').size < 3 || b.split(' ').size < 3 || a.length < 8 || b.length < 8) return false
        if (a == b) return true
        val longest = maxOf(a.length, b.length)
        return 1.0 - editDistance(a, b).toDouble() / longest >= 0.92
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1); current[0] = i + 1
            for (j in b.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = current
        }
        return previous[b.length]
    }
}
