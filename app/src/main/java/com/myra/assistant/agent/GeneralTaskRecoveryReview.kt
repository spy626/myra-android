package com.myra.assistant.agent

/**
 * User-controlled recovery RE-ENTRY, not task replay.
 * Existing task checkpoints contain no original user goal or executable plan.
 */
object GeneralTaskRecoveryReview {
    sealed interface Draft {
        data class Ready(val freshInstruction: String) : Draft
        data class Rejected(val reason: String) : Draft
    }

    /** Requires a newly entered complete instruction; simple "continue" is not authority. */
    fun prepare(raw: String, checkpoint: GeneralTaskCheckpointStore.Checkpoint?): Draft {
        if (checkpoint?.requiresReview != true || checkpoint.mayAutoResume) {
            return Draft.Rejected("No interrupted task is awaiting review")
        }
        val request = raw.trim()
        if (request.length !in 12..700 || request.any { it.isISOControl() } ||
            request.lines().size != 1) {
            return Draft.Rejected("Enter the full task goal in one line")
        }
        val vague = Regex(
            """(?iu)^(?:resume|continue|retry|start again|go ahead|""" +
                """haan|yes|same task|pichhla task|wahi task|dobara karo|""" +
                """resume task|continue task)[.! ]*$"""
        )
        if (vague.matches(request)) return Draft.Rejected(
            "Please restate the task; the old instruction was not retained")
        return Draft.Ready(request)
    }

    /**
     * A UI approval only allows drafting a NEW instruction. Fresh-screen authorization
     * and actual execution belong to the existing final-turn agent/runtime guards.
     */
    fun mayExecuteFromReview(): Boolean = false
}
