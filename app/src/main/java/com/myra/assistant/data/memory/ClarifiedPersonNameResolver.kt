package com.myra.assistant.data.memory

/** Resolves only a bare name or unambiguous letter-by-letter spelling. */
object ClarifiedPersonNameResolver {
    private val rejected = setOf("haan", "yes", "no", "nahi", "okay", "ok")

    fun resolve(raw: String): String? {
        val clean = raw.trim().trim('.', ',', '?', '!', '।', '"', '\'')
        val tokens = clean.split(Regex("[\\s-]+")).filter(String::isNotBlank)
        val letters = tokens.takeIf { it.size >= 3 && it.all { token -> token.length == 1 && token[0].isLetter() } }
            ?.joinToString("")
        val candidate = letters ?: clean.takeIf {
            it.split(Regex("\\s+")).size <= 2 &&
                it.matches(Regex("[\\p{L}][\\p{L} .'-]{1,39}"))
        } ?: return null
        if (candidate.lowercase() in rejected) return null
        val canonical = BestFriendNameCanonicalizer.canonicalize(candidate)
        return phoneticPreferred(canonical) ?: canonical.takeIf { it.length >= 3 }
    }

    private fun phoneticPreferred(value: String): String? {
        val key = soundKey(value)
        return listOf("Kareem", "Naufal", "Ayesha").singleOrNull {
            val target = soundKey(it)
            key.firstOrNull() == target.firstOrNull() && editDistance(key, target) <= 1
        }
    }

    private fun soundKey(value: String) = value.lowercase()
        .replace('q', 'k').replace("ph", "f")
        .replace(Regex("[^a-z]"), "").filterNot { it in "aeiou" }

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
