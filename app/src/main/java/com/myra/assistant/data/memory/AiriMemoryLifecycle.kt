package com.myra.assistant.data.memory

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

enum class SemanticConsolidationAction { NEW, REINFORCE, UPDATE, INVALIDATE }
enum class EpisodeReviewRating { AGAIN, HARD, GOOD, EASY }
enum class SegmentClassification { LOW_INFO, INFORMATIVE }

enum class SegmentBoundaryReason { HARD_TIME_GAP, SOFT_TIME_GAP, TOPIC_SHIFT, INTENT_SHIFT, ACTIVITY_SHIFT, STRUCTURAL_CUE }
data class SegmentBoundary(
    val afterSequence: Long,
    val hard: Boolean,
    val reason: SegmentBoundaryReason,
    val score: Double = 1.0,
    val confidence: Double = 1.0
)
data class SegmentationPlan(
    val finalized: List<LongRange>,
    val carriedTail: LongRange?,
    val boundaries: List<SegmentBoundary>
)

/**
 * Android port of Plast-Mem's stateful event-boundary pipeline. It mirrors the
 * current upstream candidate limits and scoring geometry. The last segment is
 * deliberately carried while a conversation is active; EOF is a lifecycle event,
 * never a synonym for "one user turn completed".
 */
object AiriEventSegmenter {
    const val SOFT_GAP_MS = 30L * 60L * 1000L
    const val HARD_GAP_MS = 3L * 60L * 60L * 1000L
    const val SMALL_REVIEW_LIMIT = 4
    const val PRIMITIVE_KEEP_LIMIT = 20
    const val INFORMATIVE_GROUP_LIMIT = 30
    const val MIN_SEGMENT_EVENTS = 4
    const val REVIEW_CONTEXT_EVENTS = 5
    const val TARGET_EVENTS_PER_SEGMENT = 12

    fun plan(messages: List<ConversationTruthEntity>, eof: Boolean): SegmentationPlan {
        if (messages.isEmpty()) return SegmentationPlan(emptyList(), null, emptyList())
        val sorted = messages.sortedBy { it.sequence }
        val vectors = sorted.map { FeatureHashEmbeddingProvider.embed(it.content) }
        val hardBoundaries = sorted.zipWithNext().mapNotNull { (left, right) ->
            val gap = right.committedAt - left.committedAt
            when {
                gap > HARD_GAP_MS -> SegmentBoundary(left.sequence, true, SegmentBoundaryReason.HARD_TIME_GAP)
                else -> null
            }
        }
        val hardAfter = hardBoundaries.map { it.afterSequence }.toSet()
        val partitions = mutableListOf<IntRange>()
        var partitionStart = 0
        sorted.indices.drop(1).forEach { index ->
            if (sorted[index - 1].sequence in hardAfter) {
                partitions += partitionStart until index
                partitionStart = index
            }
        }
        partitions += partitionStart until sorted.size
        val reviewed = partitions.flatMap { partition ->
            val count = partition.count()
            val budget = boundaryBudget(count)
            if (budget == 0) emptyList() else (partition.first + 1..partition.last).mapNotNull { index ->
                val local = index - partition.first
                if (local < MIN_SEGMENT_EVENTS || count - local < MIN_SEGMENT_EVENTS) return@mapNotNull null
                val leftStart = maxOf(partition.first, index - REVIEW_CONTEXT_EVENTS)
                val rightEnd = minOf(partition.last + 1, index + REVIEW_CONTEXT_EVENTS)
                val separation = 1.0 - cosine(mean(vectors.subList(leftStart, index)), mean(vectors.subList(index, rightEnd)))
                val cohesion = (cohesion(vectors.subList(leftStart, index)) + cohesion(vectors.subList(index, rightEnd))) * .5
                val gap = sorted[index].committedAt - sorted[index - 1].committedAt
                val soft = gap >= SOFT_GAP_MS
                val score = separation + .12 * cohesion + if (soft) .16 else 0.0
                SegmentBoundary(sorted[index - 1].sequence, false,
                    if (soft) SegmentBoundaryReason.SOFT_TIME_GAP else SegmentBoundaryReason.TOPIC_SHIFT,
                    score, (score / 1.25).coerceIn(0.0, 1.0))
            }.sortedByDescending { it.score }.take(budget).sortedBy { it.afterSequence }
        }
        val boundaries = (hardBoundaries + reviewed).distinctBy { it.afterSequence }.sortedBy { it.afterSequence }
        val ranges = mutableListOf<LongRange>()
        var start = sorted.first().sequence
        boundaries.forEach { boundary -> ranges += start..boundary.afterSequence; start = boundary.afterSequence + 1 }
        ranges += start..sorted.last().sequence
        val expanded = ranges.flatMap { range ->
            val count = (range.last - range.first + 1).toInt()
            if (count <= INFORMATIVE_GROUP_LIMIT) listOf(range)
            else range.chunked(TARGET_EVENTS_PER_SEGMENT)
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

    fun boundaryBudget(eventCount: Int): Int {
        if (eventCount < TARGET_EVENTS_PER_SEGMENT * 2) return 0
        return ((eventCount / TARGET_EVENTS_PER_SEGMENT) - 1).coerceAtMost((eventCount / 24).coerceIn(1, 12))
    }

    private fun mean(vectors: List<DoubleArray>): DoubleArray {
        if (vectors.isEmpty()) return DoubleArray(FeatureHashEmbeddingProvider.dimensions)
        val out = DoubleArray(vectors.first().size)
        vectors.forEach { vector -> vector.indices.forEach { out[it] += vector[it] } }
        val norm = sqrt(out.sumOf { it * it })
        return if (norm == 0.0) out else DoubleArray(out.size) { out[it] / norm }
    }

    private fun cosine(left: DoubleArray, right: DoubleArray) =
        left.indices.sumOf { left[it] * right.getOrElse(it) { 0.0 } }.coerceIn(-1.0, 1.0)

    private fun cohesion(vectors: List<DoubleArray>): Double {
        if (vectors.size <= 1) return 1.0
        val center = mean(vectors)
        return vectors.map { cosine(it, center) }.average().coerceIn(-1.0, 1.0)
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
    // Current Plast-Mem SQL uses 1 / (30 + rank) for both retrieval legs.
    private const val K = 30.0
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

/**
 * Kotlin port of the FSRS 5.2.0 / FSRS-6 inference equations used by the
 * pinned Plast-Mem worker. Training remains upstream/off-device; review state
 * transitions and current retrievability use the exact default parameter set.
 * Semantic facts never enter this episodic review path.
 */
object AiriFsrs {
    private const val DAY_MS = 86_400_000.0
    private const val DECAY = .1542
    private const val S_MIN = .001
    private const val S_MAX = 36_500.0
    private const val D_MIN = 1.0
    private const val D_MAX = 10.0
    private val W = doubleArrayOf(
        .212, 1.2931, 2.3065, 8.2956, 6.4133, .8334, 3.0194, .001,
        1.8722, .1666, .796, 1.4835, .0614, .2629, 1.6483, .6014,
        1.8729, .5425, .0912, .0658, DECAY
    )

    fun initial(rating: EpisodeReviewRating = EpisodeReviewRating.GOOD, reviewedAt: Long): FsrsState {
        val value = ratingValue(rating)
        return FsrsState(W[value - 1], initialDifficulty(value), reviewedAt)
    }

    fun retrievability(state: FsrsState, now: Long): Double {
        val reviewed = state.lastReviewedAt ?: return 1.0
        val days = ((now - reviewed).coerceAtLeast(0L) / DAY_MS)
        val factor = .9.pow(1.0 / -DECAY) - 1.0
        return (days / state.stability.coerceAtLeast(S_MIN) * factor + 1.0)
            .pow(-DECAY).coerceIn(0.0, 1.0)
    }
    fun review(state: FsrsState, rating: EpisodeReviewRating, reviewedAt: Long): FsrsState {
        if (state.lastReviewedAt != null && reviewedAt - state.lastReviewedAt < DAY_MS.toLong()) return state
        if (state.lastReviewedAt == null) return initial(rating, reviewedAt)
        val ratingValue = ratingValue(rating)
        val days = ((reviewedAt - state.lastReviewedAt).coerceAtLeast(0L) / DAY_MS).toInt()
        val stability = state.stability.coerceIn(S_MIN, S_MAX)
        val difficulty = state.difficulty.coerceIn(D_MIN, D_MAX)
        val retrievability = currentRetrievability(stability, days.toDouble())
        val nextStability = when {
            days == 0 -> stabilityShortTerm(stability, ratingValue)
            rating == EpisodeReviewRating.AGAIN -> stabilityAfterFailure(stability, retrievability, difficulty)
            else -> stabilityAfterSuccess(stability, retrievability, difficulty, ratingValue)
        }.coerceIn(S_MIN, S_MAX)
        val deltaDifficulty = -W[6] * (ratingValue - 3.0)
        val damped = (10.0 - difficulty) / 9.0 * deltaDifficulty
        val rawDifficulty = difficulty + damped
        val nextDifficulty = (W[7] * initialDifficulty(4) + (1.0 - W[7]) * rawDifficulty)
            .coerceIn(D_MIN, D_MAX)
        return FsrsState(nextStability, nextDifficulty, reviewedAt)
    }

    private fun currentRetrievability(stability: Double, days: Double): Double {
        val factor = .9.pow(1.0 / -DECAY) - 1.0
        return (days / stability * factor + 1.0).pow(-DECAY)
    }
    private fun initialDifficulty(rating: Int) =
        (W[4] - exp(W[5] * (rating - 1.0)) + 1.0).coerceIn(D_MIN, D_MAX)
    private fun stabilityAfterSuccess(stability: Double, retrievability: Double, difficulty: Double, rating: Int): Double {
        val hardPenalty = if (rating == 2) W[15] else 1.0
        val easyBonus = if (rating == 4) W[16] else 1.0
        return stability * (exp(W[8]) * (11.0 - difficulty) * stability.pow(-W[9]) *
            (exp((1.0 - retrievability) * W[10]) - 1.0) * hardPenalty * easyBonus + 1.0)
    }
    private fun stabilityAfterFailure(stability: Double, retrievability: Double, difficulty: Double): Double {
        val upper = stability / exp(W[17] * W[18])
        return (W[11] * difficulty.pow(-W[12]) * ((stability + 1.0).pow(W[13]) - 1.0) *
            exp((1.0 - retrievability) * W[14])).coerceAtMost(upper)
    }
    private fun stabilityShortTerm(stability: Double, rating: Int): Double {
        val increment = exp(W[17] * (rating - 3.0 + W[18])) * stability.pow(-W[19])
        return stability * if (rating >= 3) increment.coerceAtLeast(1.0) else increment
    }
    private fun ratingValue(rating: EpisodeReviewRating) = when (rating) {
        EpisodeReviewRating.AGAIN -> 1
        EpisodeReviewRating.HARD -> 2
        EpisodeReviewRating.GOOD -> 3
        EpisodeReviewRating.EASY -> 4
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
