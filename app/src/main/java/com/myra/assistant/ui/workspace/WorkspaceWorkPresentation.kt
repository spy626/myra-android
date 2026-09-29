package com.myra.assistant.ui.workspace

/**
 * Presentation-only state for ChatGPT-style work receipts.
 *
 * Active work always shows narrated observable milestones. Once work reaches a terminal state,
 * details collapse by default to one "Worked for …" row; expanding reveals the same narrated
 * milestones. Raw WorkspaceWorkTrace events remain untouched.
 */
internal object WorkspaceWorkPresentation {
    private const val MAX_VISIBLE_EVENTS = 8

    fun visibleEvents(
        snapshot: WorkspaceWorkSnapshot,
        expanded: Boolean,
    ): List<WorkspaceWorkEvent> {
        if (!snapshot.active && !expanded) return emptyList()
        return WorkspaceWorkNarration.events(snapshot).takeLast(MAX_VISIBLE_EVENTS)
    }

    fun compactRow(
        snapshot: WorkspaceWorkSnapshot,
        expanded: Boolean,
        nowMs: Long,
    ): String? {
        if (snapshot.active || snapshot.current == null || snapshot.startedAtMs == null) return null
        val end = snapshot.endedAtMs ?: nowMs
        val totalSeconds = ((end - snapshot.startedAtMs).coerceAtLeast(0L) / 1_000L)
            .coerceAtLeast(1L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val duration = if (minutes > 0L) "${minutes}m ${seconds}s" else "${seconds}s"
        return "Worked for $duration " + if (expanded) "⌄" else "›"
    }
}
