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
    val lastAnalyzedAt: Long = 0L
)

object ScreenContextStore {
    @Volatile private var context = ScreenContext()

    fun snapshot(): ScreenContext = context

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
        observedAt: Long
    ) {
        if (sessionId.isBlank()) return
        val validElements = elements.take(120)
        context = context.copy(
            screenSessionId = sessionId,
            currentPackage = packageName,
            currentAppName = appName,
            visibleText = validElements.map { it.label }.filter(String::isNotBlank).distinct().take(100),
            interactiveElements = validElements.filter { it.clickable },
            accessibilityTimestamp = observedAt,
            confidence = if (validElements.isEmpty()) 0.25 else 0.9
        )
    }

    @Synchronized fun onAnalysis(description: String, analyzedAt: Long) {
        context = context.copy(
            currentScreenDescription = description.trim().take(2_000),
            lastAnalyzedAt = analyzedAt
        )
    }

    @Synchronized fun invalidate() { context = ScreenContext() }
}
