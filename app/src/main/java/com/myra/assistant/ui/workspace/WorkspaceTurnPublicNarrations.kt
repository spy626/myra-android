package com.myra.assistant.ui.workspace

internal data class WorkspacePublicWorkMessage(
    val key: String,
    val statusLabel: String,
    val text: String,
    val atMs: Long,
)

internal class WorkspaceTurnPublicNarrations(
    private val maxTurns: Int = 24,
    private val maxEntriesPerTurn: Int = 8,
    private val now: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxTurns >= 2)
        require(maxEntriesPerTurn in 2..16)
    }

    private val entries = LinkedHashMap<String, LinkedHashMap<String, WorkspacePublicWorkMessage>>()

    @Synchronized fun reset(messageId: String) {
        val id = requireId(messageId)
        entries.remove(id)
        entries[id] = linkedMapOf()
        trimTurns(protectedId = id)
    }

    @Synchronized fun upsert(
        messageId: String,
        key: String,
        statusLabel: String,
        text: String,
    ): WorkspacePublicWorkMessage? {
        val id = requireId(messageId)
        if (WorkspaceSourceContext.containsPossibleSecret(statusLabel) ||
            WorkspaceSourceContext.containsPossibleSecret(text)) return null
        val semanticKey = WorkspaceWorkTrace.safeText(key, 80)
            .takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
            ?: return null
        val status = WorkspaceWorkTrace.safeText(statusLabel, 90).takeIf(String::isNotBlank)
            ?: return null
        val body = WorkspaceWorkTrace.safeText(text, 420).takeIf(String::isNotBlank)
            ?: return null

        val turn = entries.getOrPut(id) { linkedMapOf() }
        val previous = turn[semanticKey]
        val value = WorkspacePublicWorkMessage(
            key = semanticKey,
            statusLabel = status,
            text = body,
            atMs = previous?.atMs ?: now(),
        )
        turn[semanticKey] = value
        while (turn.size > maxEntriesPerTurn) turn.remove(turn.keys.first())
        trimTurns(protectedId = id)
        return value
    }

    @Synchronized fun forTurn(messageId: String): List<WorkspacePublicWorkMessage> =
        entries[requireId(messageId)]?.values?.toList().orEmpty()

    @Synchronized fun remove(messageId: String) {
        entries.remove(requireId(messageId))
    }

    @Synchronized fun clear() {
        entries.clear()
    }

    private fun trimTurns(protectedId: String) {
        while (entries.size > maxTurns) {
            val key = entries.keys.firstOrNull { it != protectedId } ?: return
            entries.remove(key)
        }
    }

    private fun requireId(messageId: String): String {
        val id = messageId.trim()
        require(id.isNotBlank() && id.length <= 128 && id.none(Char::isISOControl)) {
            "Public narration message id is invalid"
        }
        return id
    }
}
