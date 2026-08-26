package com.myra.assistant.data.memory

import java.util.Locale
import kotlin.math.abs

data class BestFriendNameCorrection(val oldName: String, val newName: String)

object BestFriendNameCorrectionParser {
    private val explicitPair = listOf(
        Regex(
            "^([\\p{L}][\\p{L} .'-]{1,39})\\s+(?:nahi|nahin|nehi|nai|not)[, ]+([\\p{L}][\\p{L} .'-]{1,39})$",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "^([\\p{L}][\\p{L} .'-]{1,39})\\s+(?:nahi|nahin|nehi|nai|not)[, ]+([\\p{L}][\\p{L} .'-]{1,39})\\s+(?:mera|meri|mere)\\s+best\\s+(?:friend|frend)\\s+(?:hai|he)$",
            RegexOption.IGNORE_CASE
        )
    )
    private val explicit = Regex(
        "^(?:(?:no|nahi|nahin|actually|sorry)[, ]+|(?:i said|maine kaha|naam)\\s+)([\\p{L}][\\p{L} .'-]{1,39})$",
        RegexOption.IGNORE_CASE
    )
    private val rejected = setOf("haan", "han", "yes", "nahi", "no", "okay", "ok", "thanks", "thank you")

    fun parse(raw: String, lastSavedName: String?): BestFriendNameCorrection? {
        val clean = raw.trim().trimEnd('.', ',', '?', '!', '।').replace(Regex("\\s+"), " ")
        explicitPair.firstNotNullOfOrNull { it.matchEntire(clean) }?.let { match ->
            return BestFriendNameCorrection(
                oldName = BestFriendNameCanonicalizer.canonicalize(match.groupValues[1]),
                newName = BestFriendNameCanonicalizer.canonicalize(match.groupValues[2])
            )
        }
        val old = lastSavedName?.takeIf { it.isNotBlank() } ?: return null
        if (clean.lowercase(Locale.ROOT) in rejected) return null
        val explicitName = explicit.matchEntire(clean)?.groupValues?.get(1)?.trim()
        val shortName = clean.takeIf {
            it.split(' ').size <= 3 && it.matches(Regex("[\\p{L}][\\p{L} .'-]{1,39}"))
        }
        val rawNew = explicitName ?: shortName ?: return null
        val newName = BestFriendNameCanonicalizer.canonicalize(rawNew)
        val canonicalOld = BestFriendNameCanonicalizer.canonicalize(old)
        if (newName.equals(canonicalOld, ignoreCase = true)) return null
        if (explicitName == null && !looksLikeSameSpokenName(canonicalOld, newName)) return null
        return BestFriendNameCorrection(old, newName)
    }

    fun needsClearCorrectedName(raw: String): Boolean {
        val clean = raw.trim().trimEnd('.', ',', '?', '!', '।').replace(Regex("\\s+"), " ")
        val match = explicitPair.firstNotNullOfOrNull { it.matchEntire(clean) } ?: return false
        return BestFriendNameCanonicalizer.canonicalize(match.groupValues[1])
            .equals(BestFriendNameCanonicalizer.canonicalize(match.groupValues[2]), ignoreCase = true)
    }

    private fun looksLikeSameSpokenName(left: String, right: String): Boolean {
        val a = soundKey(left)
        val b = soundKey(right)
        if (a.firstOrNull() != b.firstOrNull() || abs(a.length - b.length) > 2) return false
        return editDistance(a, b) <= 2
    }

    private fun soundKey(value: String): String = value.lowercase(Locale.ROOT)
        .replace("ph", "f").replace('v', 'f').replace('p', 'f')
        .replace(Regex("[^a-z]"), "")
        .filterNot { it in "aeiou" }

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
}
