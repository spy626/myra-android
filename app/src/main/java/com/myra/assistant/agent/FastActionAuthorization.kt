package com.myra.assistant.agent

enum class FastAuthorizationDecision { WAIT_FOR_FINAL, FAST_AUTHORIZE, REJECT, CANCELLED }

data class FastActionCandidate(
    val turnId: Long,
    val capability: ToolCapability,
    val parameters: Map<String, String>,
    val semanticText: String,
    val source: String,
    val confidence: Double,
    val foregroundPackage: String?,
    val windowId: Int?,
    val screenGeneration: Long,
    val firstStableAt: Long,
    val updatedAt: Long,
    val conflicting: Boolean = false
)

data class FastAuthorizationResult(val decision: FastAuthorizationDecision, val reason: String)

/** A latency gate inside unified ownership. It never interprets phrases itself: the existing
 * unified turn interpreter must independently authorize the candidate's semantic transcript. */
object FastActionAuthorizationPolicy {
    const val STABILITY_MS = 120L
    const val MAX_AGE_MS = 15_000L
    const val MIN_CONFIDENCE = .92
    private val eligible = setOf(ToolCapability.ACCESSIBILITY_SCROLL, ToolCapability.BROWSER_SEARCH)

    fun decide(
        candidate: FastActionCandidate,
        now: Long,
        speechEnded: Boolean,
        authoritativeTurnId: Long,
        unifiedDecision: AgentTurnDecision,
        currentPackage: String?,
        currentWindowId: Int?,
        protectedModal: Boolean,
        alreadyCommitted: Boolean
    ): FastAuthorizationResult {
        if (alreadyCommitted) return FastAuthorizationResult(FastAuthorizationDecision.CANCELLED, "turn_already_committed")
        if (!speechEnded) return FastAuthorizationResult(FastAuthorizationDecision.WAIT_FOR_FINAL, "speech_active")
        if (candidate.turnId <= 0L || candidate.turnId != authoritativeTurnId) return FastAuthorizationResult(FastAuthorizationDecision.REJECT, "turn_mismatch")
        if (candidate.capability !in eligible) return FastAuthorizationResult(FastAuthorizationDecision.REJECT, "capability_not_eligible")
        if (candidate.conflicting) return FastAuthorizationResult(FastAuthorizationDecision.WAIT_FOR_FINAL, "conflicting_candidates")
        if (candidate.confidence < MIN_CONFIDENCE) return FastAuthorizationResult(FastAuthorizationDecision.WAIT_FOR_FINAL, "low_confidence")
        if (now - candidate.firstStableAt < STABILITY_MS) return FastAuthorizationResult(FastAuthorizationDecision.WAIT_FOR_FINAL, "candidate_stabilizing")
        if (now - candidate.updatedAt !in 0..MAX_AGE_MS) return FastAuthorizationResult(FastAuthorizationDecision.REJECT, "stale_candidate")
        if (protectedModal) return FastAuthorizationResult(FastAuthorizationDecision.REJECT, "protected_modal")
        if (candidate.foregroundPackage != null && currentPackage != candidate.foregroundPackage) return FastAuthorizationResult(FastAuthorizationDecision.WAIT_FOR_FINAL, "foreground_changed")
        if (candidate.windowId != null && currentWindowId != candidate.windowId) return FastAuthorizationResult(FastAuthorizationDecision.WAIT_FOR_FINAL, "window_changed")
        if (!unifiedDecision.authorizesPhoneActions || unifiedDecision.intent !in setOf(TurnIntent.ACTION_REQUEST, TurnIntent.MULTI_STEP_GOAL)) {
            return FastAuthorizationResult(FastAuthorizationDecision.REJECT, "unified_semantics_not_action")
        }
        val semanticCapabilities = UnifiedLyraAgent().toStructuredIntent(
            candidate.semanticText, unifiedDecision, working = null
        ).requiredCapabilities
        if (candidate.capability !in semanticCapabilities) {
            return FastAuthorizationResult(FastAuthorizationDecision.WAIT_FOR_FINAL, "semantic_capability_mismatch")
        }
        if (candidate.capability == ToolCapability.BROWSER_SEARCH && candidate.parameters["query"].isNullOrBlank()) {
            return FastAuthorizationResult(FastAuthorizationDecision.WAIT_FOR_FINAL, "incomplete_search_query")
        }
        return FastAuthorizationResult(FastAuthorizationDecision.FAST_AUTHORIZE, "stable_safe_same_turn_candidate")
    }
}

class FastActionCandidateStore {
    @Volatile private var candidate: FastActionCandidate? = null

    @Synchronized fun stage(value: FastActionCandidate): FastActionCandidate {
        val previous = candidate
        val same = previous?.turnId == value.turnId && previous.capability == value.capability &&
            previous.parameters == value.parameters
        val conflict = previous?.turnId == value.turnId && (
            previous.capability != value.capability ||
                previous.capability == ToolCapability.ACCESSIBILITY_SCROLL &&
                previous.parameters["direction"] != value.parameters["direction"]
            )
        return value.copy(
            firstStableAt = if (same) previous!!.firstStableAt else value.updatedAt,
            conflicting = conflict || (same && previous!!.conflicting)
        ).also { candidate = it }
    }

    fun current(): FastActionCandidate? = candidate
    @Synchronized fun consume(turnId: Long): FastActionCandidate? = candidate?.takeIf { it.turnId == turnId }?.also { candidate = null }
    @Synchronized fun discard(turnId: Long) { if (candidate?.turnId == turnId) candidate = null }
}

data class FastCommittedAction(
    val turnId: Long,
    val taskId: String,
    val capability: ToolCapability,
    val parameters: Map<String, String>,
    val semanticText: String,
    val candidateAt: Long,
    val speechEndedAt: Long,
    val authorizedAt: Long,
    val dispatchedAt: Long
)

class FastCommittedActionStore {
    @Volatile private var committed: FastCommittedAction? = null
    @Synchronized fun record(value: FastCommittedAction) { committed = value }
    fun forTurn(turnId: Long): FastCommittedAction? = committed?.takeIf { it.turnId == turnId }
    @Synchronized fun clear() { committed = null }
}
