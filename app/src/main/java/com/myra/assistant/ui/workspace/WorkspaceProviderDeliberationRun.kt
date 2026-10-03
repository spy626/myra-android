package com.myra.assistant.ui.workspace

import org.json.JSONObject

/**
 * Ephemeral D4 state machine. It coordinates one proposer and one different reviewer under the
 * existing Workspace owner. It owns no persistence, provider selection, file writes or verification.
 */
internal object WorkspaceProviderDeliberationRun {
    enum class Phase {
        READY_FOR_PROPOSER,
        AWAITING_PROPOSAL,
        READY_FOR_REVIEWER,
        AWAITING_REVIEW,
        NEEDS_LOCAL_VERIFICATION,
    }

    data class Identity(
        val taskId: String,
        val specToken: String,
        val turnId: String,
        val sourceRevision: String,
    )

    data class State(
        val identity: Identity,
        val proposer: WorkspaceProviderRegistry.Id,
        val reviewer: WorkspaceProviderRegistry.Id,
        val task: String,
        val acceptanceCriteria: String,
        val phase: Phase,
        val proposerEnvelope: WorkspaceProviderDeliberation.Envelope? = null,
        val proposal: WorkspaceProviderDeliberation.Proposal? = null,
        val reviewerEnvelope: WorkspaceProviderDeliberation.Envelope? = null,
        val review: WorkspaceProviderDeliberation.Review? = null,
    )

    data class DispatchStep(
        val state: State,
        val envelope: WorkspaceProviderDeliberation.Envelope,
    )

    private const val MAX_REVIEW_JSON_CHARS = 16_000

    fun start(
        task: WorkspaceTask,
        turnId: String,
        sourceRevision: String,
        proposer: WorkspaceProviderRegistry.Id,
        reviewer: WorkspaceProviderRegistry.Id,
    ): State {
        require(WorkspaceTaskContract.isSpecApproved(task) &&
            task.status != WorkspaceTaskStatus.PAUSED) {
            "Current task specification must be explicitly approved and active before deliberation"
        }
        require(turnId.isNotBlank() && sourceRevision.isNotBlank()) {
            "Current turn/source revision is required"
        }
        require(proposer != reviewer) { "Proposer and reviewer must be different providers" }
        require(WorkspaceProviderRegistry.supports(
            proposer, WorkspaceProviderRegistry.TaskKind.CODE_EDIT)) {
            "Proposer does not support coding deliberation"
        }
        require(WorkspaceProviderRegistry.supports(
            reviewer, WorkspaceProviderRegistry.TaskKind.CODE_EDIT)) {
            "Reviewer does not support coding deliberation"
        }
        return State(
            identity = Identity(
                taskId = task.taskId,
                specToken = WorkspaceTaskContract.specToken(task),
                turnId = turnId,
                sourceRevision = sourceRevision,
            ),
            proposer = proposer,
            reviewer = reviewer,
            task = task.goal,
            acceptanceCriteria = task.acceptanceCriteria,
            phase = Phase.READY_FOR_PROPOSER,
        )
    }

    private fun requireCurrent(
        state: State,
        currentTask: WorkspaceTask,
        currentTurnId: String,
        currentSourceRevision: String,
    ) {
        require(WorkspaceTaskContract.isSpecApproved(currentTask) &&
            currentTask.status != WorkspaceTaskStatus.PAUSED) {
            "Current task is paused, changed, or no longer approved; deliberation result is stale"
        }
        require(currentTask.taskId == state.identity.taskId &&
            WorkspaceTaskContract.specToken(currentTask) == state.identity.specToken &&
            currentTurnId == state.identity.turnId &&
            currentSourceRevision == state.identity.sourceRevision) {
            "Task, turn, or source revision changed; deliberation result is stale"
        }
    }

    private fun session(state: State) = WorkspaceProviderDeliberation.Session(
        taskId = state.identity.taskId,
        turnId = state.identity.turnId,
        sourceRevision = state.identity.sourceRevision,
        task = state.task,
        acceptanceCriteria = state.acceptanceCriteria,
    )

    fun prepareProposer(
        state: State,
        currentTask: WorkspaceTask,
        currentTurnId: String,
        currentSourceRevision: String,
        source: WorkspaceProviderDeliberation.BoundedSource? = null,
        sourceApproved: Boolean = false,
    ): DispatchStep {
        require(state.phase == Phase.READY_FOR_PROPOSER) {
            "Proposer dispatch is not valid in the current deliberation phase"
        }
        requireCurrent(state, currentTask, currentTurnId, currentSourceRevision)
        val envelope = WorkspaceProviderDeliberation.proposerEnvelope(
            session(state), state.proposer, source, sourceApproved)
        return DispatchStep(
            state.copy(phase = Phase.AWAITING_PROPOSAL, proposerEnvelope = envelope),
            envelope,
        )
    }

    fun acceptProposal(
        state: State,
        currentTask: WorkspaceTask,
        currentTurnId: String,
        currentSourceRevision: String,
        rawProposal: String,
    ): State {
        require(state.phase == Phase.AWAITING_PROPOSAL) {
            "No proposer response is currently expected"
        }
        requireCurrent(state, currentTask, currentTurnId, currentSourceRevision)
        val envelope = requireNotNull(state.proposerEnvelope) {
            "Proposer envelope is unavailable"
        }
        val proposal = WorkspaceProviderDeliberation.proposal(envelope, rawProposal)
        return state.copy(
            phase = Phase.READY_FOR_REVIEWER,
            proposal = proposal,
        )
    }

    fun prepareReviewer(
        state: State,
        currentTask: WorkspaceTask,
        currentTurnId: String,
        currentSourceRevision: String,
        source: WorkspaceProviderDeliberation.BoundedSource? = null,
        sourceApproved: Boolean = false,
    ): DispatchStep {
        require(state.phase == Phase.READY_FOR_REVIEWER) {
            "Reviewer dispatch is not valid in the current deliberation phase"
        }
        requireCurrent(state, currentTask, currentTurnId, currentSourceRevision)
        val proposal = requireNotNull(state.proposal) { "Proposal is unavailable" }
        val envelope = WorkspaceProviderDeliberation.reviewerEnvelope(
            session(state), proposal, state.reviewer, source, sourceApproved)
        return DispatchStep(
            state.copy(phase = Phase.AWAITING_REVIEW, reviewerEnvelope = envelope),
            envelope,
        )
    }

    internal fun parseReview(raw: String): Pair<
        WorkspaceProviderDeliberation.Verdict, List<String>> {
        val clean = raw.trim()
        require(clean.isNotEmpty() && clean.length <= MAX_REVIEW_JSON_CHARS) {
            "Reviewer output is empty or too large"
        }
        require(clean.startsWith("{") && clean.endsWith("}")) {
            "Reviewer must return one JSON object only"
        }
        val root = runCatching { JSONObject(clean) }
            .getOrElse { throw IllegalArgumentException("Reviewer returned invalid JSON") }
        val keys = root.keys().asSequence().toSet()
        require(keys == setOf("verdict", "findings")) {
            "Reviewer JSON must contain only verdict and findings"
        }
        val verdict = runCatching {
            WorkspaceProviderDeliberation.Verdict.valueOf(
                root.getString("verdict").trim().uppercase())
        }.getOrElse {
            throw IllegalArgumentException("Reviewer verdict must be ACCEPT, REVISE, or REJECT")
        }
        val array = root.getJSONArray("findings")
        require(array.length() <= 12) { "Reviewer returned too many findings" }
        val findings = buildList {
            for (i in 0 until array.length()) {
                val item = array.opt(i)
                require(item is String) { "Reviewer finding must be text" }
                add(item)
            }
        }
        return verdict to findings
    }

    fun acceptReview(
        state: State,
        currentTask: WorkspaceTask,
        currentTurnId: String,
        currentSourceRevision: String,
        rawReview: String,
    ): State {
        require(state.phase == Phase.AWAITING_REVIEW) {
            "No reviewer response is currently expected"
        }
        requireCurrent(state, currentTask, currentTurnId, currentSourceRevision)
        val proposal = requireNotNull(state.proposal) { "Proposal is unavailable" }
        val envelope = requireNotNull(state.reviewerEnvelope) {
            "Reviewer envelope is unavailable"
        }
        val (verdict, findings) = parseReview(rawReview)
        val review = WorkspaceProviderDeliberation.review(
            envelope, proposal, verdict, findings)
        require(WorkspaceProviderDeliberation.localOutcome(proposal, review) ==
            WorkspaceProviderDeliberation.LocalOutcome.NEEDS_LOCAL_VERIFICATION)
        return state.copy(
            phase = Phase.NEEDS_LOCAL_VERIFICATION,
            review = review,
        )
    }
}
