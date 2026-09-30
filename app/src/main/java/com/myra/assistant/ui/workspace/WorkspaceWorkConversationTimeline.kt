package com.myra.assistant.ui.workspace

internal sealed interface WorkspaceWorkConversationItem {
    val atMs: Long

    data class Public(
        val message: WorkspacePublicWorkMessage,
        val liveEvent: WorkspaceWorkEvent? = null,
    ) : WorkspaceWorkConversationItem {
        override val atMs: Long get() = message.atMs
    }

    data class Work(
        val event: WorkspaceWorkEvent,
        val current: Boolean,
    ) : WorkspaceWorkConversationItem {
        override val atMs: Long get() = event.atMs
    }
}

/**
 * Presentation-only merger for the public conversation about work.
 *
 * Raw work events and public narration remain separate sources of truth. This object only decides
 * how they are interleaved on screen, suppressing a work row when an equivalent public milestone
 * already represents it.
 */
internal object WorkspaceWorkConversationTimeline {
    private const val MAX_COMPLETED_ITEMS = 16

    fun liveEventForDisplay(item: WorkspaceWorkConversationItem.Public): WorkspaceWorkEvent? =
        item.liveEvent?.copy(detail = null)

    fun active(
        snapshot: WorkspaceWorkSnapshot,
        publicMessages: List<WorkspacePublicWorkMessage>,
    ): List<WorkspaceWorkConversationItem> {
        if (!snapshot.active) return emptyList()
        val public = publicMessages.sortedBy { it.atMs }
        val current = WorkspaceWorkPresentation.visibleEvents(
            snapshot = snapshot,
            expanded = false,
        ).lastOrNull()
        val lastPublic = public.lastOrNull()
        val liveCovered = current != null &&
            lastPublic != null &&
            lastPublic.statusLabel == current.label

        return buildList {
            public.forEach { message ->
                add(
                    WorkspaceWorkConversationItem.Public(
                        message = message,
                        liveEvent = current.takeIf { liveCovered && message === lastPublic },
                    )
                )
            }
            if (current != null && !liveCovered) {
                add(WorkspaceWorkConversationItem.Work(current, current = true))
            }
        }
    }

    fun completed(
        snapshot: WorkspaceWorkSnapshot,
        publicMessages: List<WorkspacePublicWorkMessage>,
    ): List<WorkspaceWorkConversationItem> {
        if (snapshot.active || snapshot.current == null) return emptyList()
        val public = publicMessages.sortedBy { it.atMs }
        val coveredLabels = public.mapTo(mutableSetOf()) { it.statusLabel }
        val work = WorkspaceWorkPresentation.visibleEvents(
            snapshot = snapshot,
            expanded = true,
        ).filterNot { it.label in coveredLabels }

        return (work.map { WorkspaceWorkConversationItem.Work(it, current = false) } +
            public.map { WorkspaceWorkConversationItem.Public(it) })
            .sortedWith(
                compareBy<WorkspaceWorkConversationItem> { it.atMs }
                    .thenBy { if (it is WorkspaceWorkConversationItem.Work) 0 else 1 }
            )
            .takeLast(MAX_COMPLETED_ITEMS)
    }
}
