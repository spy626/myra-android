package com.myra.assistant.screen

import java.util.Locale

data class YouTubeVideoCandidate(
    val id: Int,
    val title: String,
    val contextLabel: String,
    val groupKey: String,
    val top: Int,
    val clickable: Boolean = true,
    val visible: Boolean = true,
    val semanticRole: YouTubeSemanticRole = YouTubeSemanticRole.VIDEO_PLAY_SURFACE,
    val selected: Boolean = false
)

/** Pure semantic filter used before applying an ordinal to YouTube accessibility nodes. */
object YouTubeVideoCandidatePolicy {
    fun logicalVideos(candidates: List<YouTubeVideoCandidate>): List<YouTubeVideoCandidate> =
        candidates.asSequence()
            .filter { it.visible && it.clickable }
            .filter { candidate ->
                val combined = normalize(candidate.title + " " + candidate.contextLabel)
                combined.isNotBlank() &&
                    VIDEO_SIGNAL.containsMatchIn(combined) &&
                    !AD_OR_CTA.containsMatchIn(combined) &&
                    !NON_VIDEO_CONTROL.containsMatchIn(combined)
            }
            .sortedWith(compareBy<YouTubeVideoCandidate> { it.top }.thenBy { rolePriority(it.semanticRole) })
            .groupBy { candidate ->
                candidate.groupKey.takeIf(String::isNotBlank)
                    ?: normalize(candidate.title)
            }
            .mapNotNull { (_, children) -> children.minByOrNull { rolePriority(it.semanticRole) } }
            .filter { it.semanticRole in setOf(YouTubeSemanticRole.VIDEO_PLAY_SURFACE, YouTubeSemanticRole.VIDEO_TITLE) }
            .sortedBy { it.top }
            .toList()

    fun selectOrdinal(
        candidates: List<YouTubeVideoCandidate>,
        ordinal: Int
    ): YouTubeVideoCandidate? =
        ordinal.takeIf { it > 0 }?.let { logicalVideos(candidates).getOrNull(it - 1) }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun isSafeVideoOpenRole(role: YouTubeSemanticRole): Boolean =
        role == YouTubeSemanticRole.VIDEO_PLAY_SURFACE || role == YouTubeSemanticRole.VIDEO_TITLE

    private fun rolePriority(role: YouTubeSemanticRole): Int = when (role) {
        YouTubeSemanticRole.VIDEO_PLAY_SURFACE -> 0
        YouTubeSemanticRole.VIDEO_TITLE -> 1
        else -> 10
    }

    private val VIDEO_SIGNAL = Regex(
        "(?:video[_\\s]*(?:title|thumbnail)|thumbnail|\\bviews?\\b|watching|premiere|\\blive\\b|ago|\\d{1,2}:\\d{2})",
        RegexOption.IGNORE_CASE
    )
    private val AD_OR_CTA = Regex(
        "(?:\\bsponsored\\b|\\badvertisement\\b|\\bad\\b|\\binstall\\b|learn more|visit advertiser|shop now|download app|google play)",
        RegexOption.IGNORE_CASE
    )
    private val NON_VIDEO_CONTROL = Regex(
        "(?:^|\\s)(?:home|subscriptions|library|comments?|share|like|dislike|download|save|settings|subscribe|shorts?|create|notifications?|search)(?:$|\\s)",
        RegexOption.IGNORE_CASE
    )
}
