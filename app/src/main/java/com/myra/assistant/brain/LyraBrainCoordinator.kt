package com.myra.assistant.brain

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class BrainIntent {
    CONVERSATION, PHONE_ACTION, SCREEN_ANALYSIS, SCREEN_ACTION, SCREEN_REFERENCE_ACTION, READING_REQUEST, MEMORY,
    CORRECTION, CANCELLATION, MULTI_STEP
}

data class ScreenTargetReference(
    val targetText: String? = null,
    val position: String? = null,
    val ordinal: Int? = null,
    val appPackage: String? = null,
    val activeWindowId: Int? = null,
    val screenContextGeneration: Long = 0L
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

        parseAccessibilityVideoAction(text)?.let { target ->
            val token = sequence.incrementAndGet()
            state = state.copy(
                lastScreenTarget = target,
                lastAction = "open_accessible_video",
                lastActionSucceeded = null,
                taskToken = token
            )
            return BrainDecision.ScreenAction(target, contextual = false)
        }

        parseRelativeCorrection(text)?.let { position ->
            val previous = state.lastScreenTarget
                ?: return BrainDecision.Clarify("Kaunsa item? Screen par target ek baar bata do.")
            val token = sequence.incrementAndGet()
            val corrected = previous.copy(position = position, ordinal = null)
            state = state.copy(lastScreenTarget = corrected, unresolvedReference = null, taskToken = token)
            return BrainDecision.ScreenAction(corrected, contextual = true)
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
            val token = sequence.incrementAndGet()
            state = state.copy(lastScreenTarget = next, unresolvedReference = null, taskToken = token)
            return BrainDecision.ScreenAction(next, contextual = true)
        }

        if (isRepeatReference(text)) {
            val previous = state.lastScreenTarget
                ?: return BrainDecision.Clarify("Kya dobara karna hai?")
            val token = sequence.incrementAndGet()
            state = state.copy(taskToken = token)
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
        val hasExplicitTarget = supplied.targetText != null || supplied.position != null || supplied.ordinal != null
        if (!hasExplicitTarget) return null
        // A new explicit command owns a new target. Previous target state is never
        // used to fill missing fields; only interpret() may resolve explicit contextual
        // phrases such as "open it" or "doosra wala".
        state = state.copy(lastScreenTarget = supplied, unresolvedReference = null)
        return supplied
    }

    @Synchronized fun recordScreenAction(target: ScreenTargetReference, success: Boolean) {
        state = state.copy(
            lastScreenTarget = target,
            lastAction = "screen_action",
            lastActionSucceeded = success,
            unresolvedReference = null
        )
    }

    @Synchronized fun observeForegroundApp(packageName: String?) {
        val livePackage = packageName?.trim()?.takeIf(String::isNotBlank) ?: return
        val staleYouTubeSearch = state.lastAction?.startsWith("SearchYouTube(") == true &&
            livePackage != "com.google.android.youtube"
        state = state.copy(
            currentApp = livePackage,
            lastAction = state.lastAction.takeUnless { staleYouTubeSearch },
            lastActionSucceeded = state.lastActionSucceeded.takeUnless { staleYouTubeSearch },
            lastScreenTarget = state.lastScreenTarget?.takeIf { it.appPackage == null || it.appPackage == livePackage },
            unresolvedReference = state.unresolvedReference.takeUnless { staleYouTubeSearch }
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
                Regex("\\b(?:read|padh|padho)\\b.*\\b(?:article|page|news|story)\\b|^(?:continue reading|read the next section|read only the new content|resume reading)$")
                    .containsMatchIn(text) -> BrainIntent.READING_REQUEST
                Regex("\\b(?:remember|forget|yaad|memory|bhool)\\b").containsMatchIn(text) -> BrainIntent.MEMORY
                Regex("\\b(?:nahi|no|instead|actually|doosra|other one)\\b").containsMatchIn(text) -> BrainIntent.CORRECTION
                isRepeatReference(text) || Regex("^(?:open|play|tap) (?:this|that|it)$").matches(text) -> BrainIntent.SCREEN_REFERENCE_ACTION
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
            Regex("^(?:doosra|dusra)(?: wala)?$|^(?:the other one|other one|not that one)$")
        ).any { it.matches(text) }

        private fun parseRelativeCorrection(text: String): String? = when {
            Regex("\\b(?:upar|upper|above|top)\\b").containsMatchIn(text) &&
                Regex("\\b(?:nahi|no|actually|instead|wala|one|video)\\b").containsMatchIn(text) -> "top"
            Regex("\\b(?:neeche|below|bottom)\\b").containsMatchIn(text) &&
                Regex("\\b(?:nahi|no|actually|instead|wala|one|video)\\b").containsMatchIn(text) -> "bottom"
            else -> null
        }

        private fun isRepeatReference(text: String): Boolean = listOf(
            "do that again", "same one again", "dobara karo", "phir se karo", "open it", "play it",
            "click this", "click that", "click that one", "open this", "open that", "open that one"
        ).any(text::equals)

        private fun parseAccessibilityVideoAction(text: String): ScreenTargetReference? {
            val hasVideo = Regex("\\b(?:video|वीडियो)\\b").containsMatchIn(text)
            val hasAction = Regex("\\b(?:open|play|tap|click|kholo|khol|chalao|dabao|karo)\\b")
                .containsMatchIn(text)
            if (!hasVideo || !hasAction || Regex("\\b(?:scroll|swipe)\\b").containsMatchIn(text)) return null
            val ordinal = when {
                Regex("\\b(?:third|teesra|tisra|3rd)\\b").containsMatchIn(text) -> 3
                Regex("\\b(?:second|doosra|dusra|2nd)\\b").containsMatchIn(text) -> 2
                Regex("\\b(?:first|pehla|pehli|1st)\\b").containsMatchIn(text) -> 1
                else -> null
            }
            if (ordinal != null) return ScreenTargetReference(targetText = "video", ordinal = ordinal)
            val position = when {
                Regex("\\b(?:center|middle|beech)\\b").containsMatchIn(text) -> "center"
                Regex("\\b(?:top|upar|upper)\\b").containsMatchIn(text) -> "top"
                Regex("\\b(?:bottom|neeche|niche|below)\\b").containsMatchIn(text) -> "bottom"
                Regex("\\b(?:left)\\b").containsMatchIn(text) -> "left"
                Regex("\\b(?:right)\\b").containsMatchIn(text) -> "right"
                else -> null
            }
            if (position != null) return ScreenTargetReference(targetText = "video", position = position)
            val generic = setOf(
                "open", "play", "tap", "click", "kholo", "khol", "chalao", "dabao", "karo",
                "video", "the", "a", "called", "named", "about", "wala", "wali", "please"
            )
            val title = text.split(' ').filterNot(generic::contains).joinToString(" ").trim()
            return title.takeIf { it.length >= 2 }?.let { ScreenTargetReference(targetText = it) }
        }

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
