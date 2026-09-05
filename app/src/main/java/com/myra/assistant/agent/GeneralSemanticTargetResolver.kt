package com.myra.assistant.agent

import java.util.Locale
import kotlin.math.abs

data class SemanticTargetRequest(
    val description: String,
    val roleHint: SemanticRole? = null,
    val textHint: String? = null,
    val spatialHint: SpatialHint? = null,
    val ordinal: Int? = null,
    val relativeToElementId: String? = null,
    val currentGoal: String? = null
)

enum class SpatialHint { TOP, BOTTOM, LEFT, RIGHT, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER }

data class TargetCandidateScore(
    val element: SemanticElement,
    val score: Double,
    val reasons: List<String>,
    val fingerprint: String = SemanticTargetFingerprint.of(element)
)

sealed interface SemanticTargetResolution {
    data class Unique(
        val element: SemanticElement,
        val confidence: Double,
        val matchingReasons: List<String> = emptyList(),
        val alternatives: List<TargetCandidateScore> = emptyList()
    ) : SemanticTargetResolution
    data class Ambiguous(val candidates: List<SemanticElement>, val scored: List<TargetCandidateScore> = emptyList()) : SemanticTargetResolution
    data object NotFound : SemanticTargetResolution
}

object SemanticTargetFingerprint {
    fun of(element: SemanticElement): String = listOf(
        element.role.name, normalize(element.label), element.horizontalPosition.name,
        element.verticalPosition.name, element.groupId.orEmpty()
    ).joinToString("|")

    private fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()
}

enum class SemanticClickMethod { ACCESSIBILITY_CLICK, ACCESSIBILITY_ANCESTOR_CLICK, GESTURE_LAST_RESORT, REJECT_STALE }

object SemanticClickPolicy {
    fun choose(exactClickable: Boolean, ancestorClickable: Boolean, freshBounds: Boolean): SemanticClickMethod = when {
        exactClickable -> SemanticClickMethod.ACCESSIBILITY_CLICK
        ancestorClickable -> SemanticClickMethod.ACCESSIBILITY_ANCESTOR_CLICK
        freshBounds -> SemanticClickMethod.GESTURE_LAST_RESORT
        else -> SemanticClickMethod.REJECT_STALE
    }
}

/** Converts an authorized natural-language target into semantic constraints, never coordinates. */
object SemanticTargetRequestParser {
    fun parse(raw: String, working: WorkingTaskContext? = null): SemanticTargetRequest {
        val text = normalize(raw)
        val words = text.split(' ').filter(String::isNotBlank)
        val ordinal = words.firstNotNullOfOrNull(::ordinal)
        val top = words.any { it in TOP }; val bottom = words.any { it in BOTTOM }
        val left = words.any { it in LEFT }; val right = words.any { it in RIGHT }
        val spatial = when {
            top && right -> SpatialHint.TOP_RIGHT
            top && left -> SpatialHint.TOP_LEFT
            bottom && right -> SpatialHint.BOTTOM_RIGHT
            bottom && left -> SpatialHint.BOTTOM_LEFT
            top -> SpatialHint.TOP
            bottom -> SpatialHint.BOTTOM
            left -> SpatialHint.LEFT
            right -> SpatialHint.RIGHT
            words.any { it in CENTER } -> SpatialHint.CENTER
            else -> null
        }
        val role = when {
            words.any { it in SETTINGS } -> SemanticRole.SETTINGS
            words.any { it in BACK } -> SemanticRole.BACK
            words.any { it in CLOSE } -> SemanticRole.CLOSE
            words.any { it in RESULT } -> SemanticRole.RESULT
            words.any { it in INPUT } -> SemanticRole.TEXT_INPUT
            words.any { it in LINK } -> SemanticRole.LINK
            words.any { it in BUTTON } -> SemanticRole.BUTTON
            words.any { it in ICON } -> SemanticRole.ICON
            else -> null
        }
        val meaningful = words.filterNot { it in GENERIC || it in TOP || it in BOTTOM || it in LEFT || it in RIGHT ||
            it in CENTER || it in BUTTON || it in ICON || it in RESULT || ordinal(it) != null }
        val deicticOnly = meaningful.all { it in DEICTIC || it in OPEN }
        val textHint = meaningful.filterNot { it in DEICTIC || it in OPEN || it in SETTINGS || it in BACK || it in CLOSE }
            .joinToString(" ").takeIf { it.isNotBlank() }
        return SemanticTargetRequest(raw, role, textHint, spatial, ordinal,
            if (deicticOnly) working?.currentReference else null,
            working?.searchQuery ?: working?.currentGoal ?: working?.lastCompletedTask?.query)
    }

    private fun ordinal(word: String): Int? = when (word) {
        "first", "1st", "pehla", "pehli", "पहला", "पहली" -> 1
        "second", "2nd", "doosra", "dusra", "दूसरा", "दूसरी" -> 2
        "third", "3rd", "teesra", "तीसरा" -> 3
        "last", "aakhri", "आखिरी" -> Int.MAX_VALUE
        else -> null
    }
    private fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()
    private val TOP = setOf("top", "upper", "upar", "ऊपर")
    private val BOTTOM = setOf("bottom", "lower", "niche", "neeche", "नीचे")
    private val LEFT = setOf("left", "baaye", "baye", "बाएं", "बायें")
    private val RIGHT = setOf("right", "daaye", "daye", "दाएं", "दायें")
    private val CENTER = setOf("center", "centre", "middle", "beech", "बीच")
    private val SETTINGS = setOf("setting", "settings", "gear", "cog", "preferences", "सेटिंग", "सेटिंग्स")
    private val BACK = setOf("back", "peeche", "piche", "वापस", "पीछे")
    private val CLOSE = setOf("close", "dismiss", "cancel", "बंद")
    private val RESULT = setOf("result", "results", "article", "card", "item", "नतीजा")
    private val INPUT = setOf("input", "field", "textbox")
    private val LINK = setOf("link", "article")
    private val BUTTON = setOf("button", "btn", "बटन")
    private val ICON = setOf("icon", "symbol", "आइकन")
    private val OPEN = setOf("open", "kholo", "khol", "tap", "click", "press", "dabao", "खोलो", "दबाओ")
    private val DEICTIC = setOf("this", "that", "it", "ye", "yeh", "woh", "wo", "isko", "usko", "wala", "wali", "ये", "यह", "वो", "इसे", "इसको")
    private val GENERIC = OPEN + DEICTIC + setOf("jo", "hai", "sabse", "interesting", "please", "karo", "kar", "do", "the", "one", "jaisa", "जैसा", "जो", "है")
}

/** App-independent, multi-signal ranking against one fresh normalized scene. */
class GeneralSemanticTargetResolver {
    fun resolve(request: SemanticTargetRequest, scene: ScreenScene, rejected: Set<String> = emptySet()): SemanticTargetResolution {
        val actionable = scene.semanticElements.filter { element ->
            element.actionable && element.enabled && element.right > element.left && element.bottom > element.top &&
                element.id !in rejected && SemanticTargetFingerprint.of(element) !in rejected
        }
        request.relativeToElementId?.let { id ->
            actionable.firstOrNull { it.id == id }?.let { return SemanticTargetResolution.Unique(it, .99, listOf("active_reference")) }
        }
        if (request.roleHint == null && request.textHint == null && request.spatialHint == null && request.ordinal == null &&
            !request.description.contains("interesting", true)
        ) {
            return when (actionable.size) {
                0 -> SemanticTargetResolution.NotFound
                1 -> SemanticTargetResolution.Unique(actionable.single(), .75, listOf("only_actionable_target"))
                else -> SemanticTargetResolution.Ambiguous(actionable.take(5))
            }
        }
        var candidates = actionable
        if (request.ordinal != null) {
            val resultLike = candidates.filter { it.role in RESULT_ROLES }
            if (resultLike.isNotEmpty()) candidates = resultLike
            candidates = candidates.distinctBy { it.groupId ?: it.id }.sortedWith(compareBy<SemanticElement> { it.top }.thenBy { it.left })
            val index = if (request.ordinal == Int.MAX_VALUE) candidates.lastIndex else request.ordinal - 1
            return candidates.getOrNull(index)?.let { SemanticTargetResolution.Unique(it, .96, listOf("ordinal=${request.ordinal}")) }
                ?: SemanticTargetResolution.NotFound
        }
        val scored = candidates.map { score(it, request, scene) }.filter { it.score >= MIN_SCORE }.sortedByDescending { it.score }
        val best = scored.firstOrNull() ?: return SemanticTargetResolution.NotFound
        val near = scored.takeWhile { best.score - it.score < AMBIGUITY_MARGIN }
        if (near.size > 1 || best.score < STRONG_SCORE) {
            return SemanticTargetResolution.Ambiguous(near.ifEmpty { scored.take(3) }.map { it.element }, scored.take(5))
        }
        return SemanticTargetResolution.Unique(best.element, best.score.coerceAtMost(.99), best.reasons, scored.drop(1).take(3))
    }

    private fun score(element: SemanticElement, request: SemanticTargetRequest, scene: ScreenScene): TargetCandidateScore {
        var score = .18; val reasons = mutableListOf("visible_actionable")
        if (element.clickable) { score += .12; reasons += "clickable" }
        request.roleHint?.let { role ->
            val compatible = roleCompatible(role, element.role, element.label)
            score += if (compatible) .34 else -.22
            if (compatible) reasons += "role=${role.name}"
        }
        request.textHint?.let { hint ->
            val similarity = tokenSimilarity(hint, listOf(element.text, element.contentDescription, element.hint, element.label).joinToString(" "))
            score += similarity * .46
            if (similarity > 0) reasons += "text_similarity=${"%.2f".format(Locale.ROOT, similarity)}"
        }
        request.spatialHint?.let { hint ->
            val spatial = spatialScore(element, hint, scene, null)
            score += spatial * .28; reasons += "position=${hint.name}:${"%.2f".format(Locale.ROOT, spatial)}"
        }
        request.currentGoal?.takeIf { request.description.contains("interesting", true) || request.roleHint == SemanticRole.RESULT }?.let { goal ->
            val relevance = tokenSimilarity(goal, element.label)
            if (element.role in RESULT_ROLES) { score += .12; reasons += "result_candidate" }
            score += relevance * .35
            if (relevance > 0) reasons += "goal_relevance=${"%.2f".format(Locale.ROOT, relevance)}"
        }
        return TargetCandidateScore(element, score.coerceIn(0.0, 1.0), reasons)
    }

    private fun roleCompatible(requested: SemanticRole, actual: SemanticRole, label: String): Boolean {
        if (requested == actual) return true
        if (requested == SemanticRole.RESULT && actual in RESULT_ROLES) return true
        if (requested == SemanticRole.ICON && actual in setOf(SemanticRole.ICON, SemanticRole.IMAGE, SemanticRole.BUTTON, SemanticRole.SETTINGS)) return true
        if (requested == SemanticRole.SETTINGS && actual in setOf(SemanticRole.SETTINGS, SemanticRole.ICON, SemanticRole.BUTTON))
            return Regex("\\b(?:settings?|preferences?|gear|cog)\\b", RegexOption.IGNORE_CASE).containsMatchIn(label)
        return false
    }
    private fun tokenSimilarity(a: String, b: String): Double {
        val left = tokens(a); val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val exact = left.intersect(right).size.toDouble() / left.size
        val fuzzy = left.count { l -> right.any { r -> l.startsWith(r) || r.startsWith(l) } }.toDouble() / left.size
        return maxOf(exact, fuzzy * .8)
    }
    private fun tokens(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").split(' ').filter { it.length >= 2 }.toSet()
    private fun spatialScore(element: SemanticElement, hint: SpatialHint?, scene: ScreenScene, anchor: SemanticElement?): Double {
        if (hint == null) return 1.0
        val maxX = scene.semanticElements.maxOfOrNull { it.right }?.coerceAtLeast(1) ?: 1
        val maxY = scene.semanticElements.maxOfOrNull { it.bottom }?.coerceAtLeast(1) ?: 1
        val x = element.centerX.toDouble() / maxX; val y = element.centerY.toDouble() / maxY
        if (anchor != null) return when (hint) {
            SpatialHint.TOP -> if (element.bottom <= anchor.top) 1.0 else 0.0
            SpatialHint.BOTTOM -> if (element.top >= anchor.bottom) 1.0 else 0.0
            SpatialHint.LEFT -> if (element.right <= anchor.left) 1.0 else 0.0
            SpatialHint.RIGHT -> if (element.left >= anchor.right) 1.0 else 0.0
            else -> 0.0
        }
        return when (hint) {
            SpatialHint.TOP -> 1.0 - y; SpatialHint.BOTTOM -> y
            SpatialHint.LEFT -> 1.0 - x; SpatialHint.RIGHT -> x
            SpatialHint.TOP_LEFT -> (2.0 - x - y) / 2.0; SpatialHint.TOP_RIGHT -> (x + 1.0 - y) / 2.0
            SpatialHint.BOTTOM_LEFT -> (1.0 - x + y) / 2.0; SpatialHint.BOTTOM_RIGHT -> (x + y) / 2.0
            SpatialHint.CENTER -> 1.0 - (abs(x - .5) + abs(y - .5))
        }.coerceIn(0.0, 1.0)
    }

    companion object {
        const val STRONG_SCORE = .50; const val MIN_SCORE = .32; const val AMBIGUITY_MARGIN = .10
        private val RESULT_ROLES = setOf(SemanticRole.RESULT, SemanticRole.CARD, SemanticRole.LIST_ITEM, SemanticRole.LINK, SemanticRole.VIDEO_CARD, SemanticRole.VIDEO)
    }
}
