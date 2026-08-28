package com.myra.assistant.brain

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class BrainIntent {
    CONVERSATION, PHONE_ACTION, SCREEN_ANALYSIS, SCREEN_ACTION, MEMORY,
    CORRECTION, CANCELLATION, MULTI_STEP
}

data class ScreenTargetReference(
    val targetText: String? = null,
    val position: String? = null,
    val ordinal: Int? = null
)

data class BrainTaskState(
    val currentApp: String? = null,
    val recentUserRequest: String? = null,
    val lastScreenTarget: ScreenTargetReference? = null,
    val lastAction: String? = null,
    val lastActionSucceeded: Boolean? = null,
    val unresolvedReference: String? = null,
    val currentTopic: String? = null,
    val taskToken: Long = 0L
)

sealed interface BrainDecision {
    data object PassThrough : BrainDecision
    data class Cancel(val taskToken: Long) : BrainDecision
    data class ScreenAction(val target: ScreenTargetReference, val contextual: Boolean) : BrainDecision
    data class ScrollThenOpenVideo(
        val direction: ScrollDirection,
        val ordinal: Int,
        val taskToken: Long
    ) : BrainDecision
    data class Clarify(val message: String) : BrainDecision
}

enum class ScrollDirection { DOWN, UP }

/**
 * Short-lived orchestration state. Domain operations remain owned by Room,
 * MediaProjection, AccessibilityService and Gemini Live.
 */
class LyraBrainCoordinator {
    private val sequence = AtomicLong(0L)
    @Volatile private var state = BrainTaskState()

    fun snapshot(): BrainTaskState = state

    @Synchronized fun interpret(raw: String): BrainDecision {
        val text = normalize(raw)
        if (text.isBlank()) return BrainDecision.PassThrough
        state = state.copy(recentUserRequest = raw.trim())

        if (isCancellation(text)) {
            val token = sequence.incrementAndGet()
            state = state.copy(
                lastAction = "cancel",
                lastActionSucceeded = true,
                unresolvedReference = null,
                taskToken = token
            )
            return BrainDecision.Cancel(token)
        }

        parseMultiStep(text)?.let { (direction, ordinal) ->
            val token = sequence.incrementAndGet()
            state = state.copy(
                lastAction = "scroll_then_open_video",
                lastActionSucceeded = null,
                taskToken = token
            )
            return BrainDecision.ScrollThenOpenVideo(direction, ordinal, token)
        }

        if (isOtherReference(text)) {
            val previous = state.lastScreenTarget
                ?: return BrainDecision.Clarify("Kaunsa doosra item? Screen par target ek baar bata do.")
            if (previous.ordinal == null && previous.position == null) {
                return BrainDecision.Clarify("Kaunsa doosra item? Position ya title ek baar bata do.")
            }
            val next = previous.copy(
                // If an ordered target was selected, move to its next sibling.
                ordinal = previous.ordinal?.plus(1) ?: 2,
                // A positional selection needs the previous resolved element excluded
                // by the accessibility layer; keeping the position preserves scope.
                position = previous.position
            )
            state = state.copy(lastScreenTarget = next, unresolvedReference = null)
            return BrainDecision.ScreenAction(next, contextual = true)
        }

        if (isRepeatReference(text)) {
            val previous = state.lastScreenTarget
                ?: return BrainDecision.Clarify("Kya dobara karna hai?")
            return BrainDecision.ScreenAction(previous, contextual = true)
        }

        return BrainDecision.PassThrough
    }

    @Synchronized fun resolveScreenTarget(
        targetText: String?,
        position: String?,
        ordinal: Int?
    ): ScreenTargetReference? {
        val supplied = ScreenTargetReference(
            targetText = targetText?.trim()?.takeIf(String::isNotBlank),
            position = position?.trim()?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() && it != "unspecified" },
            ordinal = ordinal?.takeIf { it > 0 }
        )
        val resolved = if (supplied.targetText != null || supplied.position != null || supplied.ordinal != null) {
            supplied
        } else state.lastScreenTarget
        if (resolved != null) state = state.copy(lastScreenTarget = resolved, unresolvedReference = null)
        return resolved
    }

    @Synchronized fun recordScreenAction(target: ScreenTargetReference, success: Boolean) {
        state = state.copy(
            lastScreenTarget = target,
            lastAction = "screen_action",
            lastActionSucceeded = success,
            unresolvedReference = null
        )
    }

    @Synchronized fun recordPhoneAction(app: String?, action: String, success: Boolean) {
        state = state.copy(
            currentApp = app?.takeIf { success } ?: state.currentApp,
            lastAction = action,
            lastActionSucceeded = success,
            unresolvedReference = null
        )
    }

    fun isTaskCurrent(token: Long): Boolean = state.taskToken == token

    @Synchronized fun finishTask(token: Long, success: Boolean) {
        if (state.taskToken != token) return
        state = state.copy(lastActionSucceeded = success)
    }

    @Synchronized fun clearTransientState() {
        val token = sequence.incrementAndGet()
        state = BrainTaskState(currentApp = state.currentApp, taskToken = token)
    }

    companion object {
        fun classify(raw: String): BrainIntent {
            val text = normalize(raw)
            return when {
                isCancellation(text) -> BrainIntent.CANCELLATION
                parseMultiStep(text) != null -> BrainIntent.MULTI_STEP
                Regex("\\b(?:remember|forget|yaad|memory|bhool)\\b").containsMatchIn(text) -> BrainIntent.MEMORY
                Regex("\\b(?:nahi|no|instead|actually|doosra|other one)\\b").containsMatchIn(text) -> BrainIntent.CORRECTION
                Regex("\\b(?:what.*screen|screen.*kya|kya dikh|read this|explain this)\\b").containsMatchIn(text) -> BrainIntent.SCREEN_ANALYSIS
                Regex("\\b(?:tap|click|open|kholo|khol|chalao|dabao)\\b").containsMatchIn(text) &&
                    Regex("\\b(?:video|button|item|result|card|wala|one|it|beech|upar|neeche|left|right|usko|isko)\\b").containsMatchIn(text) -> BrainIntent.SCREEN_ACTION
                Regex("\\b(?:open|close|scroll|search|back|home|pause|play)\\b").containsMatchIn(text) -> BrainIntent.PHONE_ACTION
                else -> BrainIntent.CONVERSATION
            }
        }

        private fun isCancellation(text: String): Boolean = listOf(
            Regex("^(?:no[, ]*)?(?:stop|cancel)(?: it| that)?$"),
            Regex("^(?:never mind|nevermind|rehne do|chhodo|mat karo|dont do that|do not do that)$")
        ).any { it.matches(text) }

        private fun isOtherReference(text: String): Boolean = listOf(
            Regex("^(?:nahi|no)[, ]+(?:ye|this|that)? ?(?:wala|one)? ?(?:nahi)?[, ]*(?:doosra|dusra|other)(?: wala| one)?$"),
            Regex("^(?:ye|this|that) wala nahi[, ]*(?:doosra|dusra|other)(?: wala| one)?$"),
            Regex("^(?:doosra|dusra|the other one|other one)$")
        ).any { it.matches(text) }

        private fun isRepeatReference(text: String): Boolean = listOf(
            "do that again", "same one again", "dobara karo", "phir se karo", "open it", "play it"
        ).any(text::equals)

        private fun parseMultiStep(text: String): Pair<ScrollDirection, Int>? {
            val hasScroll = Regex("\\b(?:scroll|swipe)\\b").containsMatchIn(text)
            val hasOpen = Regex("\\b(?:open|kholo|khol|chalao|play)\\b").containsMatchIn(text)
            val hasVideo = Regex("\\b(?:video|result|item|one|wala)\\b").containsMatchIn(text)
            if (!hasScroll || !hasOpen || !hasVideo) return null
            val direction = if (Regex("\\b(?:up|upar|upper)\\b").containsMatchIn(text)) {
                ScrollDirection.UP
            } else ScrollDirection.DOWN
            val ordinal = when {
                Regex("\\b(?:third|teesra|tisra|3rd)\\b").containsMatchIn(text) -> 3
                Regex("\\b(?:second|doosra|dusra|2nd)\\b").containsMatchIn(text) -> 2
                else -> 1
            }
            return direction to ordinal
        }

        private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
