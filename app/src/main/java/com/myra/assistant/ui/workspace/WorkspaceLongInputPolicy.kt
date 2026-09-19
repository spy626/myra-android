package com.myra.assistant.ui.workspace

/** One source of truth for local, untruncated paste limits. */
internal object WorkspaceLongInputPolicy {
    // Deliberately distinct from any free provider's input/context limit. We preserve a
    // user's full local draft, and reject an oversized remote request BEFORE sending it.
    const val MAX_MESSAGE_CHARS = 64_000
    // Local history budget, not a promise that every free model supports this context.
    const val MAX_REQUEST_CHARS = 96_000
    const val MAX_RECENT_MESSAGES = 24
    const val MAX_CODING_TASK_CHARS = 500

    fun sendable(text: String): Boolean = text.isNotBlank() && text.length <= MAX_MESSAGE_CHARS
    fun requestFits(messages: List<WorkspaceConversationStore.Message>): Boolean =
        messages.lastOrNull()?.let { it.text.length <= MAX_MESSAGE_CHARS } == true

    /** Select only complete preceding messages, with the latest user turn always intact. */
    fun outbound(messages: List<WorkspaceConversationStore.Message>): List<WorkspaceConversationStore.Message> {
        require(messages.lastOrNull()?.role == "user") { "A user message is required" }
        require(requestFits(messages)) { "Prompt exceeds the free-route request limit; full message remains saved locally" }
        val selected = mutableListOf<WorkspaceConversationStore.Message>()
        var remaining = MAX_REQUEST_CHARS
        for (message in messages.takeLast(MAX_RECENT_MESSAGES).asReversed()) {
            if (message.text.length > remaining) break
            selected.add(message)
            remaining -= message.text.length
        }
        return selected.asReversed()
    }
}
