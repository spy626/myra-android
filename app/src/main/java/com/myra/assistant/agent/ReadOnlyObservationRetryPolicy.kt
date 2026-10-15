package com.myra.assistant.agent

/**
 * Autonomous evidence-only recovery. May refresh public read-only results after delayed
 * navigation; never retries the physical action or replays a privileged tool.
 */
object ReadOnlyObservationRetryPolicy {
    const val DELAY_MS = 400L
    const val MAX_EXTRA_SAMPLES = 2
    private val safeCapabilities = setOf(
        ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH,
        ToolCapability.OBSERVE_SCREEN, ToolCapability.OPEN_APP,
    )

    fun shouldResample(
        step: GeneralPlanStep,
        dispatchAccepted: Boolean,
        before: PerceptionSnapshot,
        after: PerceptionSnapshot?,
        priorExtraSamples: Int,
    ): Boolean {
        if (!dispatchAccepted || step.capability !in safeCapabilities ||
            priorExtraSamples !in 0 until MAX_EXTRA_SAMPLES ||
            step.risk != ToolRisk.LOW ||
            before.taskId != step.taskId ||
            before.scene.modal != ModalKind.NONE) return false
        if (after == null) return true
        if (after.taskId != step.taskId ||
            after.scene.modal != ModalKind.NONE ||
            ModalSafetyPolicy.requiresAuthorization(after.scene)) return false
        // A wrong app or an unrelated changing page is a failure, not a reason to
        // blindly poll or dispatch another command.
        val result = GeneralVerifier().verify(step, before, after)
        return result.status == GeneralVerificationStatus.UNKNOWN && (
            result.mismatchReason in setOf("stale_observation", "insufficient_evidence") ||
            // Navigation may have begun but an otherwise safe public page is still blank.
            (result.mismatchReason == "expected_state_missing" &&
                after.scene.semanticElements.isEmpty() &&
                after.scene.externalForegroundPackage == before.scene.externalForegroundPackage)
        )
    }
}
