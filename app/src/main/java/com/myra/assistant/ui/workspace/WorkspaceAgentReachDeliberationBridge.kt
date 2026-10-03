package com.myra.assistant.ui.workspace

/**
 * Ephemeral bridge from pinned public GitHub evidence into the existing D4 proposer/reviewer flow.
 *
 * It owns no network execution, credentials, provider selection, project writes or verification.
 * The same verified evidence pack is bound across both seats; each provider keeps an independent
 * source-sharing approval through WorkspaceProviderDeliberationOrchestrator.Access.
 */
internal object WorkspaceAgentReachDeliberationBridge {
    data class ProposerStage(
        val packSha256: String,
        val sourceRevision: String,
        val source: WorkspaceProviderDeliberation.BoundedSource,
        val snapshot: WorkspaceProviderDeliberationOrchestrator.Snapshot,
        val step: WorkspaceProviderDeliberationOrchestrator.ProposerStep,
    )

    data class ReviewerStage(
        val packSha256: String,
        val sourceRevision: String,
        val source: WorkspaceProviderDeliberation.BoundedSource,
        val snapshot: WorkspaceProviderDeliberationOrchestrator.Snapshot,
        val step: WorkspaceProviderDeliberationOrchestrator.ReviewerStep,
    )

    private fun verifiedSource(
        pack: WorkspaceAgentReachDeliberationEvidence.Pack,
    ): WorkspaceProviderDeliberation.BoundedSource {
        require(pack.revision.isNotBlank()) { "Pinned GitHub evidence revision is missing" }
        require(pack.includedFiles.isNotEmpty()) {
            "GitHub evidence pack contains no approved bounded content"
        }
        val source = WorkspaceProviderDeliberation.boundedSource(pack.revision, pack.text)
        require(source.sha256 == pack.sha256) {
            "GitHub evidence pack hash mismatch"
        }
        return source
    }

    private fun requireSamePack(
        expectedSha256: String,
        expectedRevision: String,
        pack: WorkspaceAgentReachDeliberationEvidence.Pack,
    ): WorkspaceProviderDeliberation.BoundedSource {
        val source = verifiedSource(pack)
        require(pack.sha256 == expectedSha256 && source.revision == expectedRevision) {
            "Pinned GitHub evidence changed during provider deliberation"
        }
        return source
    }

    fun start(
        task: WorkspaceTask,
        turnId: String,
        pack: WorkspaceAgentReachDeliberationEvidence.Pack,
        proposer: WorkspaceProviderDeliberationOrchestrator.Access,
        reviewer: WorkspaceProviderDeliberationOrchestrator.Access,
    ): ProposerStage {
        val source = verifiedSource(pack)
        val snapshot = WorkspaceProviderDeliberationOrchestrator.Snapshot(
            task = task,
            turnId = turnId,
            sourceRevision = source.revision,
        )
        val step = WorkspaceProviderDeliberationOrchestrator.start(
            snapshot = snapshot,
            proposer = proposer,
            reviewer = reviewer,
            source = source,
        )
        return ProposerStage(
            packSha256 = pack.sha256,
            sourceRevision = source.revision,
            source = source,
            snapshot = snapshot,
            step = step,
        )
    }

    fun acceptProposerAndPrepareReviewer(
        stage: ProposerStage,
        currentTask: WorkspaceTask,
        currentTurnId: String,
        pack: WorkspaceAgentReachDeliberationEvidence.Pack,
        rawProposal: String,
        reviewer: WorkspaceProviderDeliberationOrchestrator.Access,
    ): ReviewerStage {
        val source = requireSamePack(stage.packSha256, stage.sourceRevision, pack)
        val current = WorkspaceProviderDeliberationOrchestrator.Snapshot(
            task = currentTask,
            turnId = currentTurnId,
            sourceRevision = source.revision,
        )
        val step = WorkspaceProviderDeliberationOrchestrator.acceptProposerAndPrepareReviewer(
            proposerStep = stage.step,
            current = current,
            rawProposal = rawProposal,
            reviewer = reviewer,
            source = source,
        )
        return ReviewerStage(
            packSha256 = stage.packSha256,
            sourceRevision = stage.sourceRevision,
            source = source,
            snapshot = current,
            step = step,
        )
    }

    fun acceptReviewer(
        stage: ReviewerStage,
        currentTask: WorkspaceTask,
        currentTurnId: String,
        pack: WorkspaceAgentReachDeliberationEvidence.Pack,
        rawReview: String,
    ): WorkspaceProviderDeliberationRun.State {
        val source = requireSamePack(stage.packSha256, stage.sourceRevision, pack)
        val current = WorkspaceProviderDeliberationOrchestrator.Snapshot(
            task = currentTask,
            turnId = currentTurnId,
            sourceRevision = source.revision,
        )
        return WorkspaceProviderDeliberationOrchestrator.acceptReviewer(
            reviewerStep = stage.step,
            current = current,
            rawReview = rawReview,
        )
    }
}
