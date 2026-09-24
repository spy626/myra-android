package com.myra.assistant.ui.workspace

import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local provider health evidence. No persistence, ranking, retry loop or routing authority.
 *
 * Only definite HTTP rate-limit/service-unavailable evidence creates a short send guard.
 * Network/timeout ambiguity is recorded for diagnostics but NEVER creates a retry/fallback signal.
 */
internal object WorkspaceProviderSessionHealth {
    private const val DEFAULT_RATE_LIMIT_MS = 60_000L
    private const val DEFAULT_TEMPORARY_OUTAGE_MS = 20_000L

    data class Snapshot(
        val state: WorkspaceProviderRegistry.HealthState,
        val blockedUntilMs: Long?,
    )

    private val state = ConcurrentHashMap<WorkspaceProviderRegistry.Id, Snapshot>()

    private fun retryAfterSeconds(header: String?): Long? =
        header?.takeIf { it.length in 1..6 && it.all(Char::isDigit) }
            ?.toLongOrNull()?.takeIf { it in 1..86_400 }

    fun recordHttp(
        id: WorkspaceProviderRegistry.Id,
        code: Int,
        retryAfterHeader: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val health = WorkspaceProviderRegistry.classifyHttp(
            id, code, retryAfterSeconds(retryAfterHeader))
        val blockedUntil = when (health.state) {
            WorkspaceProviderRegistry.HealthState.COOLDOWN ->
                nowMs + (health.retryAfterMillis ?: DEFAULT_RATE_LIMIT_MS)
            WorkspaceProviderRegistry.HealthState.TEMPORARILY_UNAVAILABLE ->
                nowMs + DEFAULT_TEMPORARY_OUTAGE_MS
            else -> null
        }
        if (health.state == WorkspaceProviderRegistry.HealthState.HEALTHY) {
            state.remove(id)
        } else {
            state[id] = Snapshot(health.state, blockedUntil)
        }
    }

    fun recordResponse(response: Response, nowMs: Long = System.currentTimeMillis()) {
        WorkspaceProviderRegistry.idForEndpoint(response.request.url.toString())?.let { id ->
            recordHttp(id, response.code, response.header("Retry-After"), nowMs)
        }
    }

    fun recordUncertainNetworkFailure(id: WorkspaceProviderRegistry.Id) {
        state[id] = Snapshot(WorkspaceProviderRegistry.HealthState.UNCERTAIN_OUTCOME, null)
    }

    fun snapshot(
        id: WorkspaceProviderRegistry.Id,
        nowMs: Long = System.currentTimeMillis(),
    ): Snapshot? {
        val current = state[id] ?: return null
        val until = current.blockedUntilMs
        if (until != null && until <= nowMs) {
            state.remove(id, current)
            return null
        }
        return current
    }

    fun canSend(
        id: WorkspaceProviderRegistry.Id,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = snapshot(id, nowMs)?.blockedUntilMs == null

    fun remainingMillis(
        id: WorkspaceProviderRegistry.Id,
        nowMs: Long = System.currentTimeMillis(),
    ): Long = snapshot(id, nowMs)?.blockedUntilMs?.minus(nowMs)?.coerceAtLeast(0L) ?: 0L

    fun cooldownMessage(
        id: WorkspaceProviderRegistry.Id,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val remaining = remainingMillis(id, nowMs)
        if (remaining <= 0L) return ""
        val seconds = ((remaining + 999L) / 1_000L).coerceAtLeast(1L)
        return "${WorkspaceProviderRegistry.capability(id).displayName} is cooling down for about ${seconds}s " +
            "after a definite rate-limit/service response. Nothing was sent. Try again after the cooldown."
    }

    internal fun clearForTests() = state.clear()
}
