package com.myra.assistant.agent

/**
 * Bridges exactly one still-unfinished browser research goal to the user's next explicit
 * named-link selection. This is task continuation metadata, not long-term memory.
 */
internal object BrowserResearchSourceHandoff {
    const val MAX_AGE_MS = 2L * 60_000L
    const val REQUIRED_OUTCOME =
        "search_and_fresh_observation_verified_goal_not_yet_complete"

    data class Pending(
        val taskId: String,
        val query: String,
        val completedAt: Long,
    ) {
        val claimKey: String get() = "$taskId:$completedAt"
    }

    private val sensitive = Regex(
        """(?iu)\b(?:otp|password|passphrase|passcode|pin|cvv|api[ -]?key|""" +
            """access[ -]?token|private[ -]?key|secret|bank account|card number|""" +
            """aadhaar|aadhar)\b"""
    )

    fun pending(context: WorkingTaskContext, nowMs: Long): Pending? {
        val completed = context.lastCompletedTask ?: return null
        val taskId = completed.taskId?.trim().orEmpty()
        val query = completed.query?.trim().orEmpty()
        if (taskId.isBlank() || query.length !in 2..140 ||
            query.any(Char::isISOControl) || sensitive.containsMatchIn(query) ||
            completed.destination != SearchDestination.BROWSER ||
            completed.completionState != TaskCompletionState.UNKNOWN ||
            completed.observedOutcome != REQUIRED_OUTCOME ||
            completed.completedAt <= 0L || completed.completedAt > nowMs ||
            nowMs - completed.completedAt > MAX_AGE_MS
        ) return null
        return Pending(taskId, query, completed.completedAt)
    }
}
