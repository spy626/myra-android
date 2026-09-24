package com.myra.assistant.ui.workspace

/** Observable Workspace activity only. It never stores private chain-of-thought. */
internal enum class WorkspaceWorkPhase {
    THINKING,
    SEARCHING,
    VISITING,
    READING,
    CODING,
    VERIFYING,
    RECOVERING,
    DONE,
    ERROR,
}

internal data class WorkspaceWorkEvent(
    val phase: WorkspaceWorkPhase,
    val label: String,
    val detail: String?,
    val atMs: Long,
)

internal data class WorkspaceWorkSnapshot(
    val events: List<WorkspaceWorkEvent>,
    val startedAtMs: Long?,
    val endedAtMs: Long?,
) {
    val current: WorkspaceWorkEvent? get() = events.lastOrNull()
    val active: Boolean get() = current?.phase !in setOf(null, WorkspaceWorkPhase.DONE, WorkspaceWorkPhase.ERROR)

    fun compactLabel(nowMs: Long): String {
        val last = current ?: return ""
        if (last.phase == WorkspaceWorkPhase.DONE && startedAtMs != null) {
            val end = endedAtMs ?: nowMs
            val seconds = ((end - startedAtMs).coerceAtLeast(0L) / 1_000L).coerceAtLeast(1L)
            return "Worked for ${seconds}s"
        }
        return when (last.phase) {
            WorkspaceWorkPhase.ERROR -> last.label.ifBlank { "Work stopped" }
            else -> last.label
        }
    }
}

/**
 * Small in-memory work receipt for UI rendering.
 * Only real observable stages should be recorded: provider request state, source/file actions,
 * verification, browser/search adapters, tests, etc. Never add hidden reasoning.
 */
internal class WorkspaceWorkTrace(
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val MAX_EVENTS = 32
        private const val MAX_LABEL_CHARS = 90
        private const val MAX_DETAIL_CHARS = 180

        private val secretAssignment = Regex(
            """(?i)\b(api[ _-]?key|token|password|passwd|client[ _-]?secret|authorization)\b\s*[:=]\s*\S+"""
        )
        private val bearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+\-/]+=*""")
        private val knownKey = Regex("""\b(?:sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9_]{8,})\b""")

        internal fun safeText(raw: String, max: Int): String {
            val flat = raw.replace(Regex("""[\r\n\t]+"""), " ")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
            val redacted = flat.replace(secretAssignment, "$1=[redacted]")
                .replace(bearer, "Bearer [redacted]")
                .replace(knownKey, "[redacted]")
            return redacted.filterNot(Char::isISOControl).take(max)
        }
    }

    private val events = ArrayDeque<WorkspaceWorkEvent>()
    private var startedAtMs: Long? = null
    private var endedAtMs: Long? = null

    @Synchronized fun clear() {
        events.clear()
        startedAtMs = null
        endedAtMs = null
    }

    @Synchronized fun begin(phase: WorkspaceWorkPhase, label: String, detail: String? = null) {
        clear()
        addLocked(phase, label, detail)
    }

    @Synchronized fun add(phase: WorkspaceWorkPhase, label: String, detail: String? = null) {
        if (events.isEmpty() || currentTerminal()) {
            clear()
        }
        addLocked(phase, label, detail)
    }

    @Synchronized fun finishSuccess(label: String = "Done", detail: String? = null) {
        if (events.isEmpty()) return
        addLocked(WorkspaceWorkPhase.DONE, label, detail)
        endedAtMs = events.last().atMs
    }

    @Synchronized fun finishError(label: String = "Work stopped", detail: String? = null) {
        if (events.isEmpty()) return
        addLocked(WorkspaceWorkPhase.ERROR, label, detail)
        endedAtMs = events.last().atMs
    }

    @Synchronized fun snapshot(): WorkspaceWorkSnapshot =
        WorkspaceWorkSnapshot(events.toList(), startedAtMs, endedAtMs)

    private fun currentTerminal(): Boolean =
        events.lastOrNull()?.phase in setOf(WorkspaceWorkPhase.DONE, WorkspaceWorkPhase.ERROR)

    private fun addLocked(phase: WorkspaceWorkPhase, label: String, detail: String?) {
        val safeLabel = safeText(label, MAX_LABEL_CHARS).ifBlank { return }
        val safeDetail = detail?.let { safeText(it, MAX_DETAIL_CHARS) }?.takeIf { it.isNotBlank() }
        val timestamp = now()
        if (startedAtMs == null) startedAtMs = timestamp
        if (phase !in setOf(WorkspaceWorkPhase.DONE, WorkspaceWorkPhase.ERROR)) endedAtMs = null

        // Do not spam the timeline when render/report emits the same real state repeatedly.
        val last = events.lastOrNull()
        if (last?.phase == phase && last.label == safeLabel && last.detail == safeDetail) return

        events.addLast(WorkspaceWorkEvent(phase, safeLabel, safeDetail, timestamp))
        while (events.size > MAX_EVENTS) events.removeFirst()
    }
}
