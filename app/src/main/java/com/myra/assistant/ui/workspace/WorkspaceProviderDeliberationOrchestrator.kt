package com.myra.assistant.ui.workspace

import okhttp3.Response

/**
 * Network-ready D4 coordinator under the existing Workspace owner.
 *
 * It prepares exactly one proposer seat followed by one different reviewer seat. It does not execute
 * calls, persist credentials/state, write files, retry, select fallback providers, or verify work.
 */
internal object WorkspaceProviderDeliberationOrchestrator {
    data class Snapshot(
        val task: WorkspaceTask,
        val turnId: String,
        val sourceRevision: String,
    )

    data class Access(
        val provider: WorkspaceProviderRegistry.Id,
        val key: String,
        val enabled: Boolean,
        val sourceApproved: Boolean,
        val zaiModel: String = WorkspaceZaiFree.DEFAULT_TEXT_MODEL,
    )

    data class ProposerStep(
        val state: WorkspaceProviderDeliberationRun.State,
        val dispatch: WorkspaceProviderDeliberationTransport.Dispatch,
    )

    data class ReviewerStep(
        val state: WorkspaceProviderDeliberationRun.State,
        val dispatch: WorkspaceProviderDeliberationTransport.Dispatch,
    )

    private fun requireSeat(
        expected: WorkspaceProviderRegistry.Id,
        access: Access,
        label: String,
    ) {
        require(access.provider == expected) {
            "$label access does not match the deliberation seat"
        }
        require(access.key.length <= 512 && access.key.none { it == '\n' || it == '\r' }) {
            "$label key is malformed"
        }
        val cooldown = WorkspaceProviderSessionHealth.cooldownMessage(expected)
        require(cooldown.isBlank()) { cooldown }
    }

    fun start(
        snapshot: Snapshot,
        proposer: Access,
        reviewer: Access,
        source: WorkspaceProviderDeliberation.BoundedSource? = null,
    ): ProposerStep {
        require(proposer.provider != reviewer.provider) {
            "Proposer and reviewer must be different providers"
        }
        val initial = WorkspaceProviderDeliberationRun.start(
            task = snapshot.task,
            turnId = snapshot.turnId,
            sourceRevision = snapshot.sourceRevision,
            proposer = proposer.provider,
            reviewer = reviewer.provider,
        )
        requireSeat(initial.proposer, proposer, "Proposer")
        val prepared = WorkspaceProviderDeliberationRun.prepareProposer(
            state = initial,
            currentTask = snapshot.task,
            currentTurnId = snapshot.turnId,
            currentSourceRevision = snapshot.sourceRevision,
            source = source,
            sourceApproved = proposer.sourceApproved,
        )
        val dispatch = WorkspaceProviderDeliberationTransport.prepare(
            envelope = prepared.envelope,
            key = proposer.key,
            providerEnabled = proposer.enabled,
            sourceApproved = proposer.sourceApproved,
            zaiModel = proposer.zaiModel,
        )
        return ProposerStep(prepared.state, dispatch)
    }

    fun acceptProposerAndPrepareReviewer(
        proposerStep: ProposerStep,
        current: Snapshot,
        rawProposal: String,
        reviewer: Access,
        source: WorkspaceProviderDeliberation.BoundedSource? = null,
    ): ReviewerStep {
        requireSeat(proposerStep.state.reviewer, reviewer, "Reviewer")
        val accepted = WorkspaceProviderDeliberationRun.acceptProposal(
            state = proposerStep.state,
            currentTask = current.task,
            currentTurnId = current.turnId,
            currentSourceRevision = current.sourceRevision,
            rawProposal = rawProposal,
        )
        val prepared = WorkspaceProviderDeliberationRun.prepareReviewer(
            state = accepted,
            currentTask = current.task,
            currentTurnId = current.turnId,
            currentSourceRevision = current.sourceRevision,
            source = source,
            sourceApproved = reviewer.sourceApproved,
        )
        val dispatch = WorkspaceProviderDeliberationTransport.prepare(
            envelope = prepared.envelope,
            key = reviewer.key,
            providerEnabled = reviewer.enabled,
            sourceApproved = reviewer.sourceApproved,
            zaiModel = reviewer.zaiModel,
        )
        return ReviewerStep(prepared.state, dispatch)
    }

    fun acceptReviewer(
        reviewerStep: ReviewerStep,
        current: Snapshot,
        rawReview: String,
    ): WorkspaceProviderDeliberationRun.State =
        WorkspaceProviderDeliberationRun.acceptReview(
            state = reviewerStep.state,
            currentTask = current.task,
            currentTurnId = current.turnId,
            currentSourceRevision = current.sourceRevision,
            rawReview = rawReview,
        )

    fun read(
        expectedProvider: WorkspaceProviderRegistry.Id,
        dispatch: WorkspaceProviderDeliberationTransport.Dispatch,
        response: Response,
    ): String {
        require(dispatch.provider == expectedProvider) {
            "Provider response does not match the active deliberation seat"
        }
        WorkspaceProviderSessionHealth.recordResponse(response)
        return WorkspaceProviderDeliberationTransport.read(dispatch, response)
    }

    fun recordNetworkFailure(provider: WorkspaceProviderRegistry.Id) {
        WorkspaceProviderSessionHealth.recordUncertainNetworkFailure(provider)
    }
}
