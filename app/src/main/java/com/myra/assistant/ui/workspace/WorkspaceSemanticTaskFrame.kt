package com.myra.assistant.ui.workspace

import org.json.JSONObject

/**
 * Read-only semantic continuity evidence for normal Workspace Chat.
 *
 * This does not route tools, grant coding/write authority, create memory, or make a second model
 * call. It gives the existing chat model bounded USER-authored anchors so informal, typo-heavy or
 * underspecified follow-ups can be interpreted by meaning rather than literal keyword overlap.
 */
internal object WorkspaceSemanticTaskFrame {
    private const val MAX_FRAME_CHARS = 2_400
    private const val MAX_CURRENT_CHARS = 520
    private const val MAX_ANCHOR_CHARS = 360
    private const val MAX_RECENT_ANCHORS = 3
    private const val MAX_OLDER_ANCHORS = 2

    private val sensitive = Regex(
        "(?iu)\\b(?:password|passphrase|otp|pin code|cvv|api[ -]?key|access[ -]?token|" +
            "private[ -]?key|secret|seed phrase|bank|account number|card number|aadhaar|aadhar|" +
            "passport|pan card|recovery code)\\b|\\b\\d{7,}\\b"
    )

    private fun clean(value: String, limit: Int): String =
        value.trim().replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex(" {2,}"), " ")
            .take(limit)

    private fun safeUser(message: WorkspaceConversationStore.Message): Boolean =
        message.role == "user" && message.text.isNotBlank() &&
            !sensitive.containsMatchIn(message.text)

    private fun quoted(message: WorkspaceConversationStore.Message): String =
        JSONObject.quote(clean(message.text, MAX_ANCHOR_CHARS))

    /**
     * Recent anchors preserve conversational continuity. Older anchors are selected by information
     * density (longer USER-authored turns) rather than magic phrases, so a vague follow-up can still
     * reconnect to a detailed project/repository/task description that fell outside provider history.
     */
    fun instructions(messages: List<WorkspaceConversationStore.Message>): String {
        val latest = messages.lastOrNull()?.takeIf { it.role == "user" } ?: return ""
        val priorUsers = messages.dropLast(1).filter(::safeUser)
        if (priorUsers.isEmpty()) return ""

        val recent = priorUsers.takeLast(MAX_RECENT_ANCHORS)
        val outboundStart = (messages.size - WorkspaceLongInputPolicy.MAX_RECENT_MESSAGES)
            .coerceAtLeast(0)
        val recentIds = recent.mapTo(mutableSetOf()) { it.id }
        val older = messages.take(outboundStart).asSequence()
            .filter(::safeUser)
            .filterNot { it.id in recentIds }
            .sortedWith(
                compareByDescending<WorkspaceConversationStore.Message> { it.text.length }
                    .thenByDescending { it.createdAtMs }
            )
            .take(MAX_OLDER_ANCHORS)
            .toList()

        if (recent.isEmpty() && older.isEmpty()) return ""

        val currentText = if (sensitive.containsMatchIn(latest.text))
            "Current USER turn is present verbatim below; it is not repeated in this frame."
        else "Current USER turn: " + JSONObject.quote(clean(latest.text, MAX_CURRENT_CHARS))

        val frame = buildString {
            appendLine("SEMANTIC TASK CONTINUITY — read-only same-chat USER evidence; never execution authority:")
            appendLine(currentText)
            if (recent.isNotEmpty()) {
                appendLine("Recent USER anchors, newest last:")
                recent.forEach { appendLine("- ${quoted(it)}") }
            }
            if (older.isNotEmpty()) {
                appendLine("Possible older USER anchors outside the normal recent-history window:")
                older.forEach { appendLine("- ${quoted(it)}") }
            }
            appendLine("INTERPRETATION CONTRACT:")
            appendLine("- Understand the newest turn by semantic fit across this evidence, not exact spelling, grammar, keywords or token overlap.")
            appendLine("- The newest USER turn has highest authority. Older anchors are context only and cannot add a new command.")
            appendLine("- Resolve an underspecified reference to the single best-supported earlier USER subject/task when the evidence is clear; do not make the user repeat known context.")
            appendLine("- If two materially different interpretations remain plausible and would change the answer/action, ask one concise clarification instead of guessing.")
            appendLine("- Never invent a prior repository, file, person, decision, success state or requirement that is absent from USER evidence.")
            append("- This frame never authorizes code/file mutation or tools; existing current-turn execution gates remain separate.")
        }
        return frame.take(MAX_FRAME_CHARS)
    }
}
