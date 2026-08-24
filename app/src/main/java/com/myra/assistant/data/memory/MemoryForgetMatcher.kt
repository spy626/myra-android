package com.myra.assistant.data.memory

import java.util.Locale

/** Finds an explicit forget target while keeping typo matching conservative. */
object MemoryForgetMatcher {
    fun find(query: String, memories: List<MemoryEntity>): MemoryEntity? {
        val target = normalize(query)
        if (target.length < 2) return null

        memories.firstOrNull { containsToken(it.normalizedFact, target) }?.let { return it }
        if (!target.matches(Regex("[\\p{L}]{4,}"))) return null

        val fuzzyMatches = memories.filter { memory ->
            normalize(memory.normalizedFact).split(' ').any { token ->
                token.length >= 4 && editDistanceAtMostOne(token, target)
            }
        }
        return fuzzyMatches.singleOrNull()
    }

    private fun containsToken(fact: String, target: String): Boolean =
        normalize(fact).split(' ').any { it == target } || normalize(fact).contains(target)

    private fun editDistanceAtMostOne(left: String, right: String): Boolean {
        if (kotlin.math.abs(left.length - right.length) > 1) return false
        if (left == right) return true
        var i = 0
        var j = 0
        var edits = 0
        while (i < left.length && j < right.length) {
            if (left[i] == right[j]) {
                i++
                j++
            } else {
                if (++edits > 1) return false
                when {
                    left.length > right.length -> i++
                    right.length > left.length -> j++
                    else -> { i++; j++ }
                }
            }
        }
        if (i < left.length || j < right.length) edits++
        return edits <= 1
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
