package com.myra.assistant.ui.workspace

/** One source of truth for local, untruncated paste limits. */
internal object WorkspaceLongInputPolicy {
    // Deliberately distinct from any free provider's input/context limit. We preserve a
    // user's full local draft, and reject an oversized remote request BEFORE sending it.
    const val MAX_MESSAGE_CHARS = 64_000
    const val MAX_REQUEST_CHARS = 64_000
    const val MAX_CODING_TASK_CHARS = 500

    fun sendable(text: String): Boolean = text.isNotBlank() && text.length <= MAX_MESSAGE_CHARS
    fun requestFits(messages: List<WorkspaceConversationStore.Message>): Boolean =
        messages.takeLast(8).sumOf { it.text.length.toLong() } <= MAX_REQUEST_CHARS
}
