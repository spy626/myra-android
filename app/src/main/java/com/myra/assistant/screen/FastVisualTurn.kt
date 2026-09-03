package com.myra.assistant.screen

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

enum class FastVisualKind { QUESTION, ACTION }

data class FastVisualRequest(val kind: FastVisualKind, val semanticHint: String)

/**
 * Broad concept matcher for routing only. Gemini still interprets the goal; this
 * avoids maintaining a sentence-by-sentence command list.
 */
object FastVisualRequestClassifier {
    private val questionConcepts = setOf("see", "screen", "visible", "dikh", "dikhra", "dekh", "error", "problem", "स्क्रीन", "दिख", "देख")
    private val actionConcepts = setOf("tap", "press", "click", "dabao", "open", "khol", "subscribe", "like")
    private val visualObjects = setOf("this", "that", "ye", "yeh", "isko", "usko", "jo", "icon", "button", "thumb", "hand", "uploader", "screen")

    fun classify(text: String): FastVisualRequest? {
        val tokens = tokens(text)
        if (tokens.isEmpty()) return null
        val hasDeicticQuestion = tokens.any { it in setOf("ye", "yeh", "this", "isme", "इसमें", "यह", "ये") } &&
            tokens.any { it in setOf("kya", "what", "क्या") }
        val hasQuestion = tokens.any { token -> questionConcepts.any { similar(token, it) } } &&
            (tokens.any { it in setOf("what", "kya", "abhi", "isme", "screen", "क्या", "अभी", "इसमें") } || text.trim().endsWith("?"))
        if (hasQuestion || hasDeicticQuestion) return FastVisualRequest(FastVisualKind.QUESTION, "screen_question")
        val action = tokens.firstOrNull { token -> actionConcepts.any { similar(token, it) } }
        val visual = tokens.firstOrNull { token -> visualObjects.any { similar(token, it) } }
        return if (action != null && visual != null) FastVisualRequest(FastVisualKind.ACTION, "$action:$visual") else null
    }

    private fun tokens(text: String): List<String> = Normalizer.normalize(text, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
        .filter(String::isNotBlank)

    private fun similar(value: String, concept: String): Boolean {
        if (value == concept || value.startsWith(concept) || concept.startsWith(value) && value.length >= 4) return true
        if (value.length < 4 || concept.length < 4 || kotlin.math.abs(value.length - concept.length) > 2) return false
        var previous = IntArray(concept.length + 1) { it }
        value.forEachIndexed { i, left ->
            val next = IntArray(concept.length + 1); next[0] = i + 1
            concept.forEachIndexed { j, right ->
                next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (left == right) 0 else 1)
            }
            previous = next
        }
        return previous.last() <= 2
    }
}

data class FastVisualTurn(
    val id: String,
    val userTurnId: Long,
    val kind: FastVisualKind,
    val semanticHint: String,
    val packageName: String,
    val windowId: Int,
    val generation: Long,
    val speechEndedAt: Long,
    val startedAt: Long,
    var frameRequestedAt: Long = 0,
    var frameReadyAt: Long = 0,
    var modelRequestAt: Long = 0,
    var firstModelResponseAt: Long = 0,
    var actionResolvedAt: Long = 0,
    var actionExecutedAt: Long = 0,
    var verificationAt: Long = 0,
    var firstAudioAt: Long = 0
)

class FastVisualTurnCoordinator {
    @Volatile private var active: FastVisualTurn? = null

    @Synchronized fun begin(userTurnId: Long, request: FastVisualRequest, packageName: String,
                            windowId: Int, generation: Long, speechEndedAt: Long, now: Long): FastVisualTurn {
        return FastVisualTurn(UUID.randomUUID().toString(), userTurnId, request.kind, request.semanticHint, packageName,
            windowId, generation, speechEndedAt, now).also { active = it }
    }

    fun current(): FastVisualTurn? = active
    fun owns(id: String): Boolean = active?.id == id
    @Synchronized fun finish(id: String): FastVisualTurn? = active?.takeIf { it.id == id }?.also { active = null }
    @Synchronized fun cancel() { active = null }
}
