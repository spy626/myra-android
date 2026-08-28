package com.myra.assistant.screen

import java.util.Locale
import kotlin.math.hypot

data class ScreenTargetCandidate(
    val id: Int,
    val label: String,
    val role: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean = true
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

sealed interface ScreenTargetResolution {
    data class Selected(val candidate: ScreenTargetCandidate, val confidence: Double) : ScreenTargetResolution
    data class Ambiguous(val candidates: List<ScreenTargetCandidate>) : ScreenTargetResolution
    data object NotFound : ScreenTargetResolution
}

/** Pure ranking policy shared by accessibility actions and JVM regression tests. */
object ScreenTargetResolver {
    fun resolve(
        candidates: List<ScreenTargetCandidate>,
        targetText: String?,
        position: String?,
        ordinal: Int?,
        screenWidth: Int,
        screenHeight: Int
    ): ScreenTargetResolution {
        val clickable = candidates.filter { it.clickable && it.right > it.left && it.bottom > it.top }
        if (clickable.isEmpty()) return ScreenTargetResolution.NotFound
        val requestedRole = requestedRole(targetText)
        val roleMatches = if (requestedRole == null) clickable else clickable.filter {
            it.role.equals(requestedRole, true) || normalize(it.label).contains(requestedRole)
        }
        val positioned = roleMatches.filter { matchesPosition(it, position, screenWidth, screenHeight) }
        if (positioned.isEmpty()) return ScreenTargetResolution.NotFound
        val ordered = positioned.sortedWith(compareBy<ScreenTargetCandidate> { it.top }.thenBy { it.left })
        if (ordinal != null && ordinal > 0) {
            return ordered.getOrNull(ordinal - 1)?.let { ScreenTargetResolution.Selected(it, 1.0) }
                ?: ScreenTargetResolution.NotFound
        }

        val queryTokens = tokens(targetText).filterNot(GENERIC_WORDS::contains).toSet()
        if (queryTokens.isNotEmpty()) {
            val scored = ordered.map { candidate -> candidate to titleScore(queryTokens, candidate.label) }
                .filter { it.second >= MIN_TITLE_CONFIDENCE }
                .sortedByDescending { it.second }
            val best = scored.firstOrNull() ?: return ScreenTargetResolution.NotFound
            val second = scored.getOrNull(1)
            if (second != null && best.second - second.second < AMBIGUITY_MARGIN) {
                return ScreenTargetResolution.Ambiguous(listOf(best.first, second.first))
            }
            return ScreenTargetResolution.Selected(best.first, best.second)
        }

        if (position.equals("center", true) || position.equals("middle", true)) {
            val diagonal = hypot(screenWidth.toDouble(), screenHeight.toDouble()).coerceAtLeast(1.0)
            val ranked = ordered.sortedBy { hypot((it.centerX - screenWidth / 2).toDouble(), (it.centerY - screenHeight / 2).toDouble()) }
            val best = ranked.first()
            val confidence = 1.0 - hypot(
                (best.centerX - screenWidth / 2).toDouble(),
                (best.centerY - screenHeight / 2).toDouble()
            ) / diagonal
            return ScreenTargetResolution.Selected(best, confidence.coerceIn(0.0, 1.0))
        }
        return if (ordered.size == 1) ScreenTargetResolution.Selected(ordered.first(), 1.0)
        else ScreenTargetResolution.Ambiguous(ordered.take(3))
    }

    private fun titleScore(query: Set<String>, label: String): Double {
        val normalizedLabel = normalize(label)
        val labelTokens = tokens(label).toSet()
        val overlap = query.intersect(labelTokens).size.toDouble() / query.size.coerceAtLeast(1)
        val phrase = query.joinToString(" ")
        val phraseBonus = if (normalizedLabel.contains(phrase)) 0.35 else 0.0
        val prefixBonus = query.count { q -> labelTokens.any { it.startsWith(q) || q.startsWith(it) } }
            .toDouble() / query.size.coerceAtLeast(1) * 0.15
        return (overlap * 0.65 + phraseBonus + prefixBonus).coerceAtMost(1.0)
    }

    private fun matchesPosition(candidate: ScreenTargetCandidate, position: String?, width: Int, height: Int): Boolean = when (position?.lowercase(Locale.ROOT)) {
        "top" -> candidate.centerY < height * 0.40
        "bottom" -> candidate.centerY > height * 0.60
        "left" -> candidate.centerX < width * 0.45
        "right" -> candidate.centerX > width * 0.55
        "center", "middle" -> candidate.centerX in (width * 0.25).toInt()..(width * 0.75).toInt() &&
            candidate.centerY in (height * 0.25).toInt()..(height * 0.75).toInt()
        else -> true
    }

    private fun requestedRole(value: String?): String? = when {
        Regex("\\b(?:video|thumbnail|वीडियो)\\b", RegexOption.IGNORE_CASE).containsMatchIn(value.orEmpty()) -> "video"
        Regex("\\b(?:button|btn|बटन)\\b", RegexOption.IGNORE_CASE).containsMatchIn(value.orEmpty()) -> "button"
        else -> null
    }

    private fun tokens(value: String?): List<String> = normalize(value.orEmpty()).split(' ').filter { it.length >= 2 }
    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()

    private val GENERIC_WORDS = setOf(
        "open", "play", "tap", "click", "karo", "kholo", "chalao", "dabao", "wala", "wali",
        "video", "button", "item", "result", "the", "called", "about", "jo", "usko", "isko"
    )
    private const val MIN_TITLE_CONFIDENCE = 0.45
    private const val AMBIGUITY_MARGIN = 0.12
}
