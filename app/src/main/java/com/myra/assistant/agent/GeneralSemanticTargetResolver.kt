package com.myra.assistant.agent

data class SemanticTargetRequest(
    val description: String,
    val roleHint: SemanticRole? = null,
    val textHint: String? = null,
    val spatialHint: SpatialHint? = null,
    val ordinal: Int? = null,
    val relativeToElementId: String? = null
)

enum class SpatialHint { TOP, BOTTOM, LEFT, RIGHT, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER }

sealed interface SemanticTargetResolution {
    data class Unique(val element: SemanticElement, val confidence: Double) : SemanticTargetResolution
    data class Ambiguous(val candidates: List<SemanticElement>) : SemanticTargetResolution
    data object NotFound : SemanticTargetResolution
}

/** App-independent resolution of a model-produced semantic request against one fresh scene. */
class GeneralSemanticTargetResolver {
    fun resolve(request: SemanticTargetRequest, scene: ScreenScene, rejected: Set<String> = emptySet()): SemanticTargetResolution {
        var candidates = scene.semanticElements.asSequence()
            .filter { it.actionable && it.id !in rejected }
            .filter { request.roleHint == null || it.role == request.roleHint }
            .filter { request.textHint.isNullOrBlank() || it.label.contains(request.textHint, ignoreCase = true) }
            .toList()
        request.relativeToElementId?.let { relativeId ->
            val anchor = scene.semanticElements.firstOrNull { it.id == relativeId } ?: return SemanticTargetResolution.NotFound
            candidates = candidates.filter { candidate -> spatialScore(candidate, request.spatialHint, scene, anchor) > 0.0 }
        }
        candidates = candidates.distinctBy { it.groupId ?: it.id }
            .sortedWith(compareBy<SemanticElement> { it.top }.thenBy { it.left })
        request.ordinal?.let { oneBased ->
            if (oneBased <= 0) return SemanticTargetResolution.NotFound
            return candidates.getOrNull(oneBased - 1)?.let { SemanticTargetResolution.Unique(it, .9) }
                ?: SemanticTargetResolution.NotFound
        }
        request.spatialHint?.let { hint ->
            val scored = candidates.map { it to spatialScore(it, hint, scene, null) }.sortedByDescending { it.second }
            if (scored.isEmpty() || scored.first().second <= 0.0) return SemanticTargetResolution.NotFound
            if (scored.size > 1 && scored[0].second - scored[1].second < .08) {
                return SemanticTargetResolution.Ambiguous(scored.takeWhile { scored[0].second - it.second < .08 }.map { it.first })
            }
            return SemanticTargetResolution.Unique(scored.first().first, scored.first().second)
        }
        return when (candidates.size) {
            0 -> SemanticTargetResolution.NotFound
            1 -> SemanticTargetResolution.Unique(candidates.single(), .95)
            else -> SemanticTargetResolution.Ambiguous(candidates)
        }
    }

    private fun spatialScore(element: SemanticElement, hint: SpatialHint?, scene: ScreenScene, anchor: SemanticElement?): Double {
        if (hint == null) return 1.0
        val maxX = scene.semanticElements.maxOfOrNull { it.right }?.coerceAtLeast(1) ?: 1
        val maxY = scene.semanticElements.maxOfOrNull { it.bottom }?.coerceAtLeast(1) ?: 1
        val x = element.centerX.toDouble() / maxX
        val y = element.centerY.toDouble() / maxY
        if (anchor != null) {
            return when (hint) {
                SpatialHint.TOP -> if (element.bottom <= anchor.top) 1.0 else 0.0
                SpatialHint.BOTTOM -> if (element.top >= anchor.bottom) 1.0 else 0.0
                SpatialHint.LEFT -> if (element.right <= anchor.left) 1.0 else 0.0
                SpatialHint.RIGHT -> if (element.left >= anchor.right) 1.0 else 0.0
                else -> 0.0
            }
        }
        return when (hint) {
            SpatialHint.TOP -> 1.0 - y
            SpatialHint.BOTTOM -> y
            SpatialHint.LEFT -> 1.0 - x
            SpatialHint.RIGHT -> x
            SpatialHint.TOP_LEFT -> (2.0 - x - y) / 2.0
            SpatialHint.TOP_RIGHT -> (x + 1.0 - y) / 2.0
            SpatialHint.BOTTOM_LEFT -> (1.0 - x + y) / 2.0
            SpatialHint.BOTTOM_RIGHT -> (x + y) / 2.0
            SpatialHint.CENTER -> 1.0 - (kotlin.math.abs(x - .5) + kotlin.math.abs(y - .5))
        }
    }
}
