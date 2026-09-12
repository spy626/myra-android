package com.myra.assistant.data.memory

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

enum class SemanticConsolidationAction { NEW, REINFORCE, UPDATE, INVALIDATE }
enum class EpisodeReviewRating { AGAIN, HARD, GOOD, EASY }
enum class SegmentClassification { LOW_INFO, INFORMATIVE }

data class SegmentBoundary(val afterSequence: Long, val hard: Boolean, val reason: String)
data class SegmentationPlan(
    val finalized: List<LongRange>,
    val carriedTail: LongRange?,
    val boundaries: List<SegmentBoundary>
)

/**
 * Native deterministic equivalent of Plast-Mem's temporal first-stage segmenter.
 * Model review is intentionally not put on the voice critical path. Hard boundaries
 * are immutable; soft buckets are bounded before background semantic review.
 */
object AiriEventSegmenter {
    const val SOFT_GAP_MS = 30L * 60L * 1000L
    const val HARD_GAP_MS = 3L * 60L * 60L * 1000L
    const val SMALL_REVIEW_LIMIT = 4
    const val PRIMITIVE_KEEP_LIMIT = 20
    const val INFORMATIVE_GROUP_LIMIT = 30

    fun plan(messages: List<ConversationTruthEntity>, eof: Boolean): SegmentationPlan {
        if (messages.isEmpty()) return SegmentationPlan(emptyList(), null, emptyList())
        val sorted = messages.sortedBy { it.sequence }
        val boundaries = sorted.zipWithNext().mapNotNull { (left, right) ->
            val gap = right.committedAt - left.committedAt
            when {
                gap > HARD_GAP_MS -> SegmentBoundary(left.sequence, true, "HARD_TIME_GAP")
                gap >= SOFT_GAP_MS -> SegmentBoundary(left.sequence, false, "SOFT_TIME_GAP")
                else -> null
            }
        }
        val ranges = mutableListOf<LongRange>()
        var start = sorted.first().sequence
        boundaries.forEach { boundary -> ranges += start..boundary.afterSequence; start = boundary.afterSequence + 1 }
        ranges += start..sorted.last().sequence
        val expanded = ranges.flatMap { range ->
            val count = (range.last - range.first + 1).toInt()
            if (count <= PRIMITIVE_KEEP_LIMIT) listOf(range)
            else range.chunked(PRIMITIVE_KEEP_LIMIT)
        }
        return when {
            eof -> SegmentationPlan(expanded, null, boundaries)
            expanded.size == 1 -> SegmentationPlan(emptyList(), expanded.single(), boundaries)
            else -> SegmentationPlan(expanded.dropLast(1), expanded.last(), boundaries)
        }
    }

    fun classify(messages: List<ConversationTruthEntity>): SegmentClassification {
        val lexical = messages.sumOf { it.content.count(Char::isLetterOrDigit) }
        return if (lexical < 12) SegmentClassification.LOW_INFO else SegmentClassification.INFORMATIVE
    }

    private fun LongRange.chunked(size: Int): List<LongRange> {
        val out = mutableListOf<LongRange>(); var cursor = first
        while (cursor <= last) { val end = minOf(last, cursor + size - 1); out += cursor..end; cursor = end + 1 }
        return out
    }
}

interface LocalEmbeddingProvider {
    val dimensions: Int
    fun embed(text: String): DoubleArray
}

/** Free, private feature-hashing vector lane. No network and no paid model. */
object FeatureHashEmbeddingProvider : LocalEmbeddingProvider {
    override val dimensions = 64
    override fun embed(text: String): DoubleArray {
        val vector = DoubleArray(dimensions)
        AiriText.normalize(text).split(' ').filter { it.length >= 2 }.forEach { token ->
            val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            val slot = ((digest[0].toInt() and 0xff) shl 8 or (digest[1].toInt() and 0xff)) % dimensions
            vector[slot] += if ((digest[2].toInt() and 1) == 0) 1.0 else -1.0
        }
        val norm = sqrt(vector.sumOf { it * it }).takeIf { it > 0.0 } ?: return vector
        return DoubleArray(dimensions) { vector[it] / norm }
    }

    fun encode(vector: DoubleArray) = vector.joinToString(",") { "%.5f".format(Locale.ROOT, it) }
    fun decode(value: String): DoubleArray? = runCatching {
        value.split(',').map(String::toDouble).toDoubleArray().takeIf { it.size == dimensions }
    }.getOrNull()
    fun cosine(left: DoubleArray, right: DoubleArray): Double =
        if (left.size != right.size) 0.0 else left.indices.sumOf { left[it] * right[it] }.coerceIn(-1.0, 1.0)
}

object ReciprocalRankFusion {
    private const val K = 60.0
    fun <T> merge(lanes: List<List<T>>, key: (T) -> String, limit: Int): List<Pair<T, Double>> {
        val values = linkedMapOf<String, T>(); val scores = linkedMapOf<String, Double>()
        lanes.forEach { lane -> lane.forEachIndexed { index, item ->
            val id = key(item); values[id] = item; scores[id] = (scores[id] ?: 0.0) + 1.0 / (K + index + 1)
        } }
        return scores.entries.sortedByDescending { it.value }.take(limit)
            .mapNotNull { (id, score) -> values[id]?.let { it to score } }
    }
}

data class FsrsState(val stability: Double, val difficulty: Double, val lastReviewedAt: Long?)

/** Bounded Android FSRS-equivalent state transition; semantic facts never enter this path. */
object AiriFsrs {
    private const val DAY_MS = 86_400_000.0
    fun retrievability(state: FsrsState, now: Long): Double {
        val reviewed = state.lastReviewedAt ?: return 1.0
        val days = ((now - reviewed).coerceAtLeast(0L) / DAY_MS)
        return exp(ln(0.9) * days / state.stability.coerceAtLeast(0.1)).coerceIn(0.05, 1.0)
    }
    fun review(state: FsrsState, rating: EpisodeReviewRating, reviewedAt: Long): FsrsState {
        if (state.lastReviewedAt != null && reviewedAt - state.lastReviewedAt < DAY_MS.toLong()) return state
        val stabilityFactor = when (rating) { EpisodeReviewRating.AGAIN -> .55; EpisodeReviewRating.HARD -> .9; EpisodeReviewRating.GOOD -> 1.55; EpisodeReviewRating.EASY -> 2.1 }
        val difficultyDelta = when (rating) { EpisodeReviewRating.AGAIN -> .8; EpisodeReviewRating.HARD -> .3; EpisodeReviewRating.GOOD -> -.2; EpisodeReviewRating.EASY -> -.5 }
        return FsrsState((state.stability * stabilityFactor).coerceIn(.1, 3650.0),
            (state.difficulty + difficultyDelta).coerceIn(1.0, 10.0), reviewedAt)
    }
}

object FlashbulbPolicy {
    const val SURPRISE_THRESHOLD = .85
    fun isFlashbulb(surprise: Double, explicitForever: Boolean) = explicitForever || surprise >= SURPRISE_THRESHOLD
    fun retrievalMultiplier(base: Double, flashbulb: Boolean, surprise: Double): Double {
        val floor = if (flashbulb) (.6 + surprise.coerceIn(0.0, 1.0) * .2) else .25
        return floor + (1.0 - floor) * base
    }
}
