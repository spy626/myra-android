package com.myra.assistant.ui.workspace

/**
 * Bounded in-memory work receipts keyed by the exact USER turn that started them.
 *
 * This is display state only. It does not persist messages, grant execution authority,
 * or store hidden reasoning. Active traces are kept ahead of older terminal receipts.
 */
internal class WorkspaceTurnWorkTraces(
    private val maxTraces: Int = 24,
    private val factory: () -> WorkspaceWorkTrace = { WorkspaceWorkTrace() },
) {
    init {
        require(maxTraces >= 2) { "At least two turn traces are required for foreground/background work" }
    }

    private val traces = LinkedHashMap<String, WorkspaceWorkTrace>()

    @Synchronized fun reset(messageId: String): WorkspaceWorkTrace {
        val id = requireId(messageId)
        val trace = factory()
        traces.remove(id)
        traces[id] = trace
        trim(protectedId = id)
        return trace
    }

    @Synchronized fun getOrCreate(messageId: String): WorkspaceWorkTrace {
        val id = requireId(messageId)
        traces[id]?.let { return it }
        val trace = factory()
        traces[id] = trace
        trim(protectedId = id)
        return trace
    }

    @Synchronized fun existing(messageId: String): WorkspaceWorkTrace? =
        traces[requireId(messageId)]

    @Synchronized fun remove(messageId: String) {
        traces.remove(requireId(messageId))
    }

    @Synchronized fun clear() {
        traces.clear()
    }

    @Synchronized internal fun ids(): List<String> = traces.keys.toList()

    private fun trim(protectedId: String) {
        while (traces.size > maxTraces) {
            val terminal = traces.entries.firstOrNull {
                it.key != protectedId && !it.value.snapshot().active
            }
            val key = terminal?.key
                ?: traces.keys.firstOrNull { it != protectedId }
                ?: return
            traces.remove(key)
        }
    }

    private fun requireId(messageId: String): String {
        val id = messageId.trim()
        require(id.isNotBlank() && id.length <= 128 && id.none(Char::isISOControl)) {
            "Work-trace message id is invalid"
        }
        return id
    }
}
