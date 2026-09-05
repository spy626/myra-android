package com.myra.assistant.agent

import java.util.concurrent.atomic.AtomicLong

enum class SemanticRole {
    BUTTON, TEXT, IMAGE, VIDEO, VIDEO_CARD, LIKE_CONTROL, COMMENTS_CONTROL,
    SUBSCRIBE_CONTROL, CHANNEL_PROFILE, CHANNEL_NAME, SEARCH, NAVIGATION,
    TEXT_INPUT, SEND, MENU, TOGGLE, LINK, TAB, LIST_ITEM, SCROLL_CONTAINER,
    BACK, CLOSE, SETTINGS, RESULT, CARD, ICON, SHARE, PLAY_PAUSE, CUSTOM_CONTROL, UNKNOWN
}

enum class RelativeHorizontalPosition { LEFT, CENTER, RIGHT }
enum class RelativeVerticalPosition { TOP, MIDDLE, BOTTOM }

enum class SemanticTargetFamily {
    RESULT, ARTICLE, CARD, LIST_ITEM, BUTTON, ICON, TAB, NAVIGATION, MENU,
    SETTINGS, BACK, CLOSE, INPUT, UNKNOWN
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
    val groupId: String? = null,
    val sourceNodeId: String? = null,
    val packageName: String? = null,
    val windowId: Int? = null,
    val screenGeneration: Long? = null,
    val text: String = label,
    val contentDescription: String = "",
    val hint: String = "",
    val className: String = "",
    val clickable: Boolean = actionable,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val enabled: Boolean = true,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val horizontalPosition: RelativeHorizontalPosition = RelativeHorizontalPosition.CENTER,
    val verticalPosition: RelativeVerticalPosition = RelativeVerticalPosition.MIDDLE,
    val possibleActions: Set<ToolCapability> = if (actionable) setOf(ToolCapability.ACCESSIBILITY_CLICK) else emptySet(),
    val confidence: Double = if (label.isBlank()) .5 else .9,
    val containerId: String? = groupId,
    val logicalIndex: Int? = null,
    val parentRole: SemanticRole? = null,
    val targetFamily: SemanticTargetFamily = SemanticTargetFamily.UNKNOWN,
    val navigationElement: Boolean = false
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
            text.matches(Regex(".*\\b(?:settings?|preferences?|gear|cog)\\b.*")) -> SemanticRole.SETTINGS
            text.matches(Regex(".*\\b(?:close|dismiss|cancel|not now)\\b.*")) -> SemanticRole.CLOSE
            text.matches(Regex(".*\\b(?:back|navigate up)\\b.*")) -> SemanticRole.BACK
            text.contains("subscribe") || text.contains("subscribed") -> SemanticRole.SUBSCRIBE_CONTROL
            text.contains("comment") && (clickable || clazz.contains("button")) -> SemanticRole.COMMENTS_CONTROL
            text.contains("like") && !text.contains("comment") -> SemanticRole.LIKE_CONTROL
            text.contains("share") -> SemanticRole.SHARE
            text.contains("send") || text.contains("post") -> SemanticRole.SEND
            text.contains("search") -> SemanticRole.SEARCH
            text.contains("channel") && (text.contains("profile") || text.contains("avatar")) -> SemanticRole.CHANNEL_PROFILE
            text.contains("channel") -> SemanticRole.CHANNEL_NAME
            text.contains("video") || text.contains("thumbnail") -> SemanticRole.VIDEO
            clazz.contains("button") -> SemanticRole.BUTTON
            clazz.contains("image") -> SemanticRole.IMAGE
            clickable && clazz.contains("text") -> SemanticRole.LINK
            clickable -> SemanticRole.BUTTON
            clazz.contains("text") -> SemanticRole.TEXT
            else -> SemanticRole.UNKNOWN
        }
    }
}

object SemanticElementSemantics {
    private val navigationLabels = Regex(
        "^(?:home|menu|back|previous|next|sign in|log in|tabs?|all|images|videos|news|maps|shopping)$",
        RegexOption.IGNORE_CASE
    )

    fun isNavigation(label: String, role: SemanticRole): Boolean =
        role in setOf(SemanticRole.NAVIGATION, SemanticRole.TAB, SemanticRole.BACK, SemanticRole.MENU) ||
            navigationLabels.matches(label.trim())

    fun family(role: SemanticRole, label: String, navigation: Boolean = isNavigation(label, role)): SemanticTargetFamily = when {
        navigation -> SemanticTargetFamily.NAVIGATION
        role == SemanticRole.RESULT || role in setOf(SemanticRole.VIDEO, SemanticRole.VIDEO_CARD) -> SemanticTargetFamily.RESULT
        role == SemanticRole.CARD -> SemanticTargetFamily.CARD
        role == SemanticRole.LIST_ITEM -> SemanticTargetFamily.LIST_ITEM
        role == SemanticRole.SETTINGS -> SemanticTargetFamily.SETTINGS
        role == SemanticRole.ICON || role == SemanticRole.IMAGE -> SemanticTargetFamily.ICON
        role == SemanticRole.BUTTON -> SemanticTargetFamily.BUTTON
        role == SemanticRole.TAB -> SemanticTargetFamily.TAB
        role == SemanticRole.MENU -> SemanticTargetFamily.MENU
        role == SemanticRole.BACK -> SemanticTargetFamily.BACK
        role == SemanticRole.CLOSE -> SemanticTargetFamily.CLOSE
        role == SemanticRole.TEXT_INPUT -> SemanticTargetFamily.INPUT
        role == SemanticRole.LINK && label.trim().length >= 4 -> SemanticTargetFamily.RESULT
        else -> SemanticTargetFamily.UNKNOWN
    }
}
