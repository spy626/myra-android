package com.myra.assistant.data.memory

import java.util.Locale

/** Canonicalizes only observed, high-confidence ASR spellings of known names. */
object BestFriendNameCanonicalizer {
    private val naufal = setOf(
        "naufal", "noufal", "nauphal", "nauphala", "nau phala",
        "now pal", "nowpal", "no fall", "now fall", "noval", "naipal", "naupal"
    )
    private val kareem = setOf("kareem", "karim")
    private val ayesha = setOf("ayesha", "ayeshaa", "aisha", "aysha")

    fun canonicalize(raw: String): String {
        val clean = raw.trim().trim('.', ',', '?', '!', '\'', '"')
            .replace(Regex("\\s+"), " ")
        val normalized = clean.lowercase(Locale.ROOT)
        return when (normalized) {
            in naufal -> "Naufal"
            in kareem -> "Kareem"
            in ayesha -> "Ayesha"
            // Do not keep adding every ASR spelling to a lookup table. A corrected
            // pronunciation such as "now fal" is close enough to the established
            // canonical identity, while unrelated names remain untouched.
            else -> preferredByDistance(normalized) ?: clean.replaceFirstChar {
                if (it.isLowerCase()) it.uppercaseChar().toString() else it.toString()
            }
        }
    }

    private fun preferredByDistance(value: String): String? {
        val compact = value.replace(Regex("[^a-z]"), "")
        return preferred.singleOrNull { canonical ->
            val target = canonical.lowercase(Locale.ROOT)
            compact.firstOrNull() == target.firstOrNull() &&
                kotlin.math.abs(compact.length - target.length) <= 2 &&
                editDistance(compact, target) <= 2
        }
    }

    private fun editDistance(left: String, right: String): Int {
        val row = IntArray(right.length + 1) { it }
        left.forEachIndexed { i, a ->
            var diagonal = row[0]
            row[0] = i + 1
            right.forEachIndexed { j, b ->
                val above = row[j + 1]
                row[j + 1] = minOf(row[j + 1] + 1, row[j] + 1, diagonal + if (a == b) 0 else 1)
                diagonal = above
            }
        }
        return row.last()
    }

    private val preferred = setOf("Naufal", "Kareem", "Ayesha")

    fun isPreferredCanonical(raw: String): Boolean =
        canonicalize(raw) in preferred
}
