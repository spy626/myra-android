package com.myra.assistant.agent

import java.util.concurrent.atomic.AtomicLong

enum class SemanticRole {
    BUTTON, TEXT, IMAGE, VIDEO, VIDEO_CARD, LIKE_CONTROL, COMMENTS_CONTROL,
    SUBSCRIBE_CONTROL, CHANNEL_PROFILE, CHANNEL_NAME, SEARCH, NAVIGATION,
    TEXT_INPUT, SEND, MENU, TOGGLE, LINK, TAB, LIST_ITEM, SCROLL_CONTAINER,
    BACK, SHARE, PLAY_PAUSE, CUSTOM_CONTROL, UNKNOWN
}

data class SemanticElement(
    val id: String,
    val role: SemanticRole,
    val label: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val actionable: Boolean,
    val selected: Boolean = false,
    val groupId: String? = null
) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

data class ScreenshotReference(
    val id: String,
    val capturedAt: Long,
    val width: Int,
    val height: Int,
    val source: String = "accessibility"
)

data class CurrentActivityContext(
    val packageName: String,
    val appLabel: String? = null,
    val screenType: String = "UNKNOWN",
    val windowId: Int,
    val generation: Long,
    val visibleElements: List<SemanticElement>,
    val screenshotReference: ScreenshotReference? = null,
    val recentUserGoal: String? = null,
    val activeTaskId: String? = null,
    val confidence: Double,
    val timestamp: Long
)

object ActivityContextStore {
    private val version = AtomicLong(0)
    @Volatile private var value: CurrentActivityContext? = null

    fun snapshot(): CurrentActivityContext? = value

    @Synchronized fun update(context: CurrentActivityContext): CurrentActivityContext {
        val old = value
        val changedWindow = old == null || old.packageName != context.packageName || old.windowId != context.windowId
        val next = context.copy(generation = if (changedWindow) version.incrementAndGet() else old!!.generation)
        value = next
        return next
    }

    @Synchronized fun attachScreenshot(reference: ScreenshotReference, packageName: String, windowId: Int): Boolean {
        val current = value ?: return false
        if (current.packageName != packageName || current.windowId != windowId) return false
        value = current.copy(screenshotReference = reference, timestamp = maxOf(current.timestamp, reference.capturedAt))
        return true
    }

    @Synchronized fun invalidate() { value = null; version.incrementAndGet() }
}

object SemanticRoleClassifier {
    fun classify(label: String, className: String, clickable: Boolean, scrollable: Boolean = false): SemanticRole {
        val text = label.lowercase()
        val clazz = className.lowercase()
        return when {
            scrollable -> SemanticRole.SCROLL_CONTAINER
            clazz.contains("edittext") -> SemanticRole.TEXT_INPUT
            text.contains("subscribe") || text.contains("subscribed") -> SemanticRole.SUBSCRIBE_CONTROL
            text.contains("comment") && (clickable || clazz.contains("button")) -> SemanticRole.COMMENTS_CONTROL
            text.contains("like") && !text.contains("comment") -> SemanticRole.LIKE_CONTROL
            text.contains("share") -> SemanticRole.SHARE
            text.contains("send") || text.contains("post") -> SemanticRole.SEND
            text.contains("search") -> SemanticRole.SEARCH
            text.contains("channel") && (text.contains("profile") || text.contains("avatar")) -> SemanticRole.CHANNEL_PROFILE
            text.contains("channel") -> SemanticRole.CHANNEL_NAME
            text.contains("video") || text.contains("thumbnail") -> SemanticRole.VIDEO
            clazz.contains("button") || clickable -> SemanticRole.BUTTON
            clazz.contains("image") -> SemanticRole.IMAGE
            clazz.contains("text") -> SemanticRole.TEXT
            else -> SemanticRole.UNKNOWN
        }
    }
}
