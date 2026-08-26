package com.myra.assistant.data.memory

import java.util.Locale
import kotlin.math.abs

/** Conservative phonetic matching; callers must still require one unambiguous person group. */
object BestFriendNameSimilarity {
    fun likelySame(left: String, right: String): Boolean {
        val a = letters(left)
        val b = letters(right)
        if (a == b) return true
        if (a.length !in 4..24 || b.length !in 4..24) return false
        if (a.first() != b.first() || a.last() != b.last()) return false
        return editDistance(a, b) <= 3 && editDistance(soundKey(a), soundKey(b)) <= 1
    }

    private fun letters(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z]"), "")

    private fun soundKey(value: String): String = value
        .replace("ph", "f").replace('v', 'f').replace('p', 'f')
        .replace(Regex("([a-z])\\1+"), "\$1")
        .filterNot { it in "aeiou" }

    private fun editDistance(left: String, right: String): Int {
        if (abs(left.length - right.length) > 3) return 4
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
}
