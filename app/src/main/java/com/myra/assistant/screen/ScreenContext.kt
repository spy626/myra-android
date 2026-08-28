package com.myra.assistant.screen

/** Temporary screen-working state. It is never written to Room or disk. */
data class ScreenContext(
    val screenSessionId: String = "",
    val currentPackage: String? = null,
    val currentAppName: String? = null,
    val currentUrl: String? = null,
    val currentScreenDescription: String? = null,
    val visibleText: List<String> = emptyList(),
    val interactiveElements: List<VisibleScreenElement> = emptyList(),
    val frameId: Long = 0L,
    val frameTimestamp: Long = 0L,
    val accessibilityTimestamp: Long = 0L,
    val confidence: Double = 0.0,
    val lastAnalyzedAt: Long = 0L,
    val summary: ScreenSummary = ScreenSummary(),
    val lastScrollAt: Long = 0L
)

data class ScreenSummary(
    val packageName: String? = null,
    val appName: String? = null,
    val titles: List<String> = emptyList(),
    val buttons: List<String> = emptyList(),
    val visibleText: List<String> = emptyList(),
    val centerElement: VisibleScreenElement? = null,
    val topElement: VisibleScreenElement? = null,
    val bottomElement: VisibleScreenElement? = null,
    val builtAt: Long = 0L
)

enum class ScreenCacheUse { QUESTION, ACTION }

/** Pure freshness policy for the single in-memory ScreenContext. */
object HotScreenCachePolicy {
    private const val STATIC_QUESTION_MS = 1_500L
    private const val SCROLLING_MS = 500L
    private const val VIDEO_MS = 300L
    private const val ACTION_MS = 700L
    private const val SCROLL_ACTIVITY_MS = 1_000L

    fun maxAgeMs(packageName: String?, lastScrollAt: Long, now: Long, use: ScreenCacheUse): Long = when {
        packageName.orEmpty().contains("youtube", true) || packageName.orEmpty().contains("video", true) -> VIDEO_MS
        lastScrollAt > 0L && now - lastScrollAt <= SCROLL_ACTIVITY_MS -> SCROLLING_MS
        use == ScreenCacheUse.ACTION -> ACTION_MS
        else -> STATIC_QUESTION_MS
    }

    fun isFresh(context: ScreenContext, sessionId: String, now: Long, use: ScreenCacheUse): Boolean {
        if (sessionId.isBlank() || context.screenSessionId != sessionId || context.summary.builtAt <= 0L) return false
        val newestSnapshot = maxOf(context.frameTimestamp, context.accessibilityTimestamp)
        return newestSnapshot > 0L && (now - newestSnapshot).coerceAtLeast(0L) <=
            maxAgeMs(context.currentPackage, context.lastScrollAt, now, use)
    }
}

object ScreenContextStore {
    @Volatile private var context = ScreenContext()

    fun snapshot(): ScreenContext = context

    fun freshSnapshot(sessionId: String, now: Long, use: ScreenCacheUse): ScreenContext? =
        context.takeIf { HotScreenCachePolicy.isFresh(it, sessionId, now, use) }

    @Synchronized fun onFrame(frame: ScreenFrame) {
        if (context.screenSessionId.isNotBlank() && context.screenSessionId != frame.sessionId) {
            context = ScreenContext()
        }
        context = context.copy(
            screenSessionId = frame.sessionId,
            frameId = frame.frameId,
            frameTimestamp = frame.capturedAt
        )
    }

    @Synchronized fun onAccessibility(
        sessionId: String,
        packageName: String?,
        appName: String?,
        elements: List<VisibleScreenElement>,
        observedAt: Long,
        screenWidth: Int = 0,
        screenHeight: Int = 0
    ) {
        if (sessionId.isBlank()) return
        val validElements = elements.take(120)
        val visible = validElements.map { it.label }.filter(String::isNotBlank).distinct().take(100)
        val interactive = validElements.filter { it.clickable }
        val centerX = screenWidth.takeIf { it > 0 }?.div(2) ?: validElements.maxOfOrNull { it.bounds.right }?.div(2) ?: 0
        val centerY = screenHeight.takeIf { it > 0 }?.div(2) ?: validElements.maxOfOrNull { it.bounds.bottom }?.div(2) ?: 0
        val center = validElements.minByOrNull {
            val dx = it.bounds.centerX() - centerX
            val dy = it.bounds.centerY() - centerY
            dx.toLong() * dx + dy.toLong() * dy
        }
        val summary = ScreenSummary(
            packageName = packageName,
            appName = appName,
            titles = visible.filter { it.length >= 10 }.take(12),
            buttons = validElements.filter { it.clickable || it.className.contains("button", true) }
                .map { it.label }.distinct().take(12),
            visibleText = visible.take(30),
            centerElement = center,
            topElement = validElements.minByOrNull { it.bounds.centerY() },
            bottomElement = validElements.maxByOrNull { it.bounds.centerY() },
            builtAt = observedAt
        )
        context = context.copy(
            screenSessionId = sessionId,
            currentPackage = packageName,
            currentAppName = appName,
            visibleText = visible,
            interactiveElements = interactive,
            accessibilityTimestamp = observedAt,
            confidence = if (validElements.isEmpty()) 0.25 else 0.9,
            summary = summary
        )
    }

    @Synchronized fun markScrolling(observedAt: Long) { context = context.copy(lastScrollAt = observedAt) }

    @Synchronized fun onAnalysis(description: String, analyzedAt: Long) {
        context = context.copy(
            currentScreenDescription = description.trim().take(2_000),
            lastAnalyzedAt = analyzedAt
        )
    }

    @Synchronized fun invalidate() { context = ScreenContext() }
}
