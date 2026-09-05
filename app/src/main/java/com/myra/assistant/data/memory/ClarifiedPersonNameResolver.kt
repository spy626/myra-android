package com.myra.assistant.data.memory

sealed interface ClarifiedNameResult {
    data class Accepted(val name: String) : ClarifiedNameResult
    data class NeedsConfirmation(val heardLetters: String, val proposedName: String) : ClarifiedNameResult
    data object Unclear : ClarifiedNameResult
}

/** Dedicated conservative parser for a pending rename clarification. */
object ClarifiedPersonNameResolver {
    private val rejected = setOf("haan", "yes", "no", "nahi", "okay", "ok")
    private val preferred = listOf("Kareem", "Naufal", "Ayesha")

    fun resolve(raw: String): ClarifiedNameResult {
        val clean = raw.trim().trim('.', ',', '?', '!', '।', '"', '\'')
        if (clean.lowercase() in rejected) return ClarifiedNameResult.Unclear
        val tokens = clean.split(Regex("[\\s-]+")).filter(String::isNotBlank)
        if (tokens.size >= 3 && tokens.all { it.length == 1 && it[0].isLetter() }) {
            return resolveSpelling(tokens.map { it.uppercase() })
        }
        val bare = clean.takeIf {
            it.split(Regex("\\s+")).size <= 2 &&
                it.matches(Regex("[\\p{L}][\\p{L} .'-]{1,39}"))
        } ?: return ClarifiedNameResult.Unclear
        return ClarifiedNameResult.Accepted(BestFriendNameCanonicalizer.canonicalize(bare))
    }

    private fun resolveSpelling(letters: List<String>): ClarifiedNameResult {
        val heard = letters.joinToString("")
        preferred.singleOrNull { it.equals(heard, ignoreCase = true) }?.let {
            return ClarifiedNameResult.Accepted(it)
        }
        val proposed = preferred.singleOrNull {
            editDistance(heard.lowercase(), it.lowercase()) <= 2
        } ?: return ClarifiedNameResult.Unclear
        return ClarifiedNameResult.NeedsConfirmation(letters.joinToString("-"), proposed)
    }

    private fun editDistance(left: String, right: String): Int {
        val row = IntArray(right.length + 1) { it }
        left.forEachIndexed { i, a ->
            var diagonal = row[0]; row[0] = i + 1
            right.forEachIndexed { j, b ->
                val above = row[j + 1]
                row[j + 1] = minOf(row[j + 1] + 1, row[j] + 1, diagonal + if (a == b) 0 else 1)
                diagonal = above
            }
        }
        return row.last()
    }
}
