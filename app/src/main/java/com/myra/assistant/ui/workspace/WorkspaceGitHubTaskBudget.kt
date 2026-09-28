package com.myra.assistant.ui.workspace

/**
 * Small deterministic per-task budget for the protected GitHub coding loop.
 *
 * It is not provider ranking or retry authority. Callers must explicitly consume a unit before
 * each bounded action. The state is persisted by the owning self-edit checkpoint.
 */
internal object WorkspaceGitHubTaskBudget {
    const val MAX_PROVIDER_CALLS = 5
    const val MAX_REVIEW_CALLS = 3
    const val MAX_FALLBACK_SWITCHES = 2
    const val MAX_CI_REPAIRS = 1
    const val MAX_COMMIT_ATTEMPTS = 2
    private const val MAX_FAILURE_CHARS = 320

    data class State(
        val providerCalls: Int = 0,
        val reviewCalls: Int = 0,
        val fallbackSwitches: Int = 0,
        val ciRepairs: Int = 0,
        val commitAttempts: Int = 0,
        val lastFailure: String = "",
    )

    fun validate(state: State): State {
        require(state.providerCalls in 0..MAX_PROVIDER_CALLS) { "Provider-call budget is invalid" }
        require(state.reviewCalls in 0..MAX_REVIEW_CALLS) { "Reviewer-call budget is invalid" }
        require(state.fallbackSwitches in 0..MAX_FALLBACK_SWITCHES) { "Fallback budget is invalid" }
        require(state.ciRepairs in 0..MAX_CI_REPAIRS) { "CI-repair budget is invalid" }
        require(state.commitAttempts in 0..MAX_COMMIT_ATTEMPTS) { "Commit-attempt budget is invalid" }
        require(state.lastFailure.length <= MAX_FAILURE_CHARS &&
            state.lastFailure.none(Char::isISOControl)) {
            "Failure checkpoint text is invalid"
        }
        return state
    }

    fun consumeProvider(state: State): State = validate(
        state.copy(providerCalls = increment(
            state.providerCalls, MAX_PROVIDER_CALLS, "provider-call"
        ))
    )

    fun consumeReview(state: State): State = validate(
        state.copy(reviewCalls = increment(
            state.reviewCalls, MAX_REVIEW_CALLS, "reviewer-call"
        ))
    )

    fun consumeFallback(state: State): State = validate(
        state.copy(fallbackSwitches = increment(
            state.fallbackSwitches, MAX_FALLBACK_SWITCHES, "fallback-switch"
        ))
    )

    fun consumeCiRepair(state: State): State = validate(
        state.copy(ciRepairs = increment(
            state.ciRepairs, MAX_CI_REPAIRS, "CI-repair"
        ))
    )

    fun consumeCommit(state: State): State = validate(
        state.copy(commitAttempts = increment(
            state.commitAttempts, MAX_COMMIT_ATTEMPTS, "commit-attempt"
        ))
    )

    fun withFailure(state: State, reason: String): State {
        val safe = WorkspaceWorkTrace.safeText(reason, MAX_FAILURE_CHARS)
        return validate(state.copy(lastFailure = safe))
    }

    fun summary(state: State): String {
        validate(state)
        return "provider " + state.providerCalls + "/" + MAX_PROVIDER_CALLS +
            " · review " + state.reviewCalls + "/" + MAX_REVIEW_CALLS +
            " · fallback " + state.fallbackSwitches + "/" + MAX_FALLBACK_SWITCHES +
            " · CI repair " + state.ciRepairs + "/" + MAX_CI_REPAIRS +
            " · commit " + state.commitAttempts + "/" + MAX_COMMIT_ATTEMPTS
    }

    private fun increment(current: Int, max: Int, label: String): Int {
        require(current < max) {
            "Task " + label + " budget exhausted (" + current + "/" + max + "); task stopped safely"
        }
        return current + 1
    }
}
