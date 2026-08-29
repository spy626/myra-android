package com.myra.assistant.screen

import java.util.Locale
import kotlin.math.hypot

/** YouTube-only target policy. AccessibilityService still owns the actual click and verification. */
object YouTubeActionResolver {
    data class VideoCandidate(val element: ScreenTargetCandidate, val title: String = element.label)
    sealed interface Result {
        data class Selected(val candidate: VideoCandidate, val confidence: Double) : Result
        data class Ambiguous(val candidates: List<VideoCandidate>) : Result
        data object NotFound : Result
    }

    fun resolveVideoTarget(
        candidates: List<VideoCandidate>, targetText: String?, position: String?, ordinal: Int?,
        screenWidth: Int, screenHeight: Int
    ): Result {
        val visible = candidates.filter { it.element.clickable && it.element.right > it.element.left && it.element.bottom > it.element.top }
        if (visible.isEmpty()) return Result.NotFound
        val query = tokens(targetText).filterNot { it in GENERIC }.toSet()
        if (query.isNotEmpty()) {
            val scored = visible.map { it to titleScore(query, it.title) }
                .filter { it.second >= MIN_TITLE_CONFIDENCE }.sortedByDescending { it.second }
            val best = scored.firstOrNull() ?: return Result.NotFound
            val second = scored.getOrNull(1)
            if (second != null && best.second - second.second < AMBIGUITY_MARGIN) return Result.Ambiguous(listOf(best.first, second.first))
            return Result.Selected(best.first, best.second)
        }
        val ordered = visible.sortedWith(compareBy<VideoCandidate> { it.element.top }.thenBy { it.element.left })
        if (ordinal != null && ordinal > 0) return ordered.getOrNull(ordinal - 1)?.let { Result.Selected(it, 1.0) } ?: Result.NotFound
        if (position.equals("center", true) || position.equals("middle", true)) {
            val ranked = ordered.sortedBy { hypot((it.element.centerX - screenWidth / 2).toDouble(), (it.element.centerY - screenHeight / 2).toDouble()) }
            val best = ranked.first()
            val diagonal = hypot(screenWidth.toDouble(), screenHeight.toDouble()).coerceAtLeast(1.0)
            val distance = hypot((best.element.centerX - screenWidth / 2).toDouble(), (best.element.centerY - screenHeight / 2).toDouble())
            return Result.Selected(best, (1.0 - distance / diagonal).coerceIn(0.0, 1.0))
        }
        return if (ordered.size == 1) Result.Selected(ordered.first(), 1.0) else Result.Ambiguous(ordered.take(3))
    }

    fun verifyVideoOpened(beforePackage: String?, afterPackage: String?, beforeSignature: String, afterSignature: String, afterLooksLikeVideo: Boolean): Boolean {
        val packageChanged = !beforePackage.isNullOrBlank() && beforePackage != afterPackage
        val uiChanged = beforeSignature.isNotBlank() && beforeSignature != afterSignature
        return afterLooksLikeVideo && (packageChanged || uiChanged)
    }

    private fun titleScore(query: Set<String>, title: String): Double {
        val labelTokens = tokens(title).toSet()
        val overlap = query.intersect(labelTokens).size.toDouble() / query.size.coerceAtLeast(1)
        val phraseBonus = if (normalize(title).contains(query.joinToString(" "))) 0.35 else 0.0
        val prefixBonus = query.count { q -> labelTokens.any { it.startsWith(q) || q.startsWith(it) } }
            .toDouble() / query.size.coerceAtLeast(1) * 0.15
        return (overlap * 0.65 + phraseBonus + prefixBonus).coerceAtMost(1.0)
    }
    private fun tokens(value: String?): List<String> = normalize(value.orEmpty()).split(' ').filter { it.length >= 2 }
    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()
    private val GENERIC = setOf("open", "play", "tap", "click", "karo", "kholo", "chalao", "dabao", "wala", "wali", "video", "the", "called", "about", "this", "that")
    private const val MIN_TITLE_CONFIDENCE = 0.45
    private const val AMBIGUITY_MARGIN = 0.12
}
