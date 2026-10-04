package com.myra.assistant.agent

import com.myra.assistant.screen.RenderedBrowserNavigationPolicy

/**
 * Domain evidence adapter, NOT another task or memory owner. Browser visible content
 * change alone cannot prove navigation destination or authorize the next step.
 */
internal object BrowserNavigationTaskEvidence {
    data class Result(
        val generalStatus: GeneralVerificationStatus,
        val taskState: TaskCompletionState,
        val observed: String,
        val destinationVerified: Boolean = false,
        val permitsAutonomousContinuation: Boolean = false,
    )

    fun rejected(): Result = Result(
        generalStatus = GeneralVerificationStatus.FAILURE,
        taskState = TaskCompletionState.FAILURE,
        observed = "named_browser_link_not_dispatched; destination_unverified; next_action_not_authorized",
    )

    fun afterTap(verification: RenderedBrowserNavigationPolicy.Verification): Result = when (verification) {
        RenderedBrowserNavigationPolicy.Verification.BROWSER_CONTENT_CHANGED_URL_UNVERIFIED ->
            Result(
                generalStatus = GeneralVerificationStatus.UNKNOWN,
                taskState = TaskCompletionState.UNKNOWN,
                observed = "two_fresh_browser_observations_stable_new_visible_text;" +
                    "destination_url_unverified; next_action_not_authorized",
            )
        RenderedBrowserNavigationPolicy.Verification.UNKNOWN ->
            Result(
                generalStatus = GeneralVerificationStatus.UNKNOWN,
                taskState = TaskCompletionState.UNKNOWN,
                observed = "named_browser_link_dispatched;" +
                    "post_action_browser_result_unverified; next_action_not_authorized",
            )
    }

    /**
     * Exactly the currently accepted FINAL turn and its pre-existing Android task may
     * receive evidence. Stale callbacks MUST NOT terminate a newer task.
     */
    fun completeOwned(
        turnId: Long,
        taskId: String?,
        result: Result,
        runtime: GeneralAgentRuntime,
        working: WorkingTaskContextStore,
    ): Boolean {
        if (turnId <= 0L || taskId.isNullOrBlank()) return false
        val active = runtime.activeTask() ?: return false
        if (active.turnId != turnId || active.id != taskId ||
            working.snapshot().taskId != taskId) return false
        val terminal = runtime.completeFromAdapter(result.generalStatus, result.observed)
            ?: return false
        working.completeRuntime(terminal, result.observed, result.taskState)
        return true
    }
}
