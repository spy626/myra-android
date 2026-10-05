package com.myra.assistant.agent

/**
 * Ephemeral, user-controlled continuation for an unresolved two-source research comparison.
 *
 * This permits at most ONE additional explicitly selected public source (three total hosts).
 * It never grants navigation authority, factual-truth authority, provider sharing, memory
 * persistence, or an automatic fourth source.
 */
internal object BrowserResearchContinuation {
    const val MAX_AGE_MS = BrowserResearchComparison.MAX_AGE_MS

    enum class Reason {
        CRITICAL_LITERAL_CONFLICT,
        NO_SAFE_CLAIM_ALIGNMENT,
    }

    enum class Disposition {
        BOUNDED_SUMMARY_READY_AFTER_THIRD,
        CONFLICT_REMAINS_AFTER_THIRD,
        FINAL_UNRESOLVED_NO_ALIGNMENT,
    }

    data class Session(
        val taskId: String,
        val query: String,
        val first: BrowserResearchComparison.SourceEvidence,
        val second: BrowserResearchComparison.SourceEvidence,
        val reason: Reason,
        val createdAt: Long,
    ) {
        val hosts: Set<String> get() = setOf(first.host, second.host)
        val claimKey: String
            get() = "$taskId:${first.contentSha256}:${second.contentSha256}:$createdAt"
    }

    data class Result(
        val taskId: String,
        val query: String,
        val first: BrowserResearchComparison.SourceEvidence,
        val second: BrowserResearchComparison.SourceEvidence,
        val third: BrowserResearchComparison.SourceEvidence,
        val firstToThird: BrowserResearchComparison.Result,
        val secondToThird: BrowserResearchComparison.Result,
        val disposition: Disposition,
        val boundedSummaryReady: Boolean,
        val factualTruthVerified: Boolean = false,
        val autonomousContinuationAllowed: Boolean = false,
    )

    fun start(
        comparison: BrowserResearchComparison.Result,
        goal: BrowserResearchGoalCompletion.Assessment,
        nowMs: Long,
    ): Session? {
        val reason = when (goal.disposition) {
            BrowserResearchGoalCompletion.Disposition.UNRESOLVED_CRITICAL_LITERAL_CONFLICT ->
                Reason.CRITICAL_LITERAL_CONFLICT
            BrowserResearchGoalCompletion.Disposition.MORE_EVIDENCE_REQUIRED ->
                Reason.NO_SAFE_CLAIM_ALIGNMENT
            BrowserResearchGoalCompletion.Disposition.BOUNDED_SUMMARY_READY -> return null
        }
        if (goal.boundedSummaryReady || goal.autonomousContinuationAllowed ||
            comparison.taskId.isBlank() || comparison.query.isBlank() ||
            comparison.first.host == comparison.second.host ||
            nowMs < comparison.second.capturedAt ||
            nowMs - comparison.second.capturedAt > MAX_AGE_MS
        ) return null
        return Session(
            taskId = comparison.taskId,
            query = comparison.query,
            first = comparison.first,
            second = comparison.second,
            reason = reason,
            createdAt = nowMs,
        )
    }

    fun fresh(session: Session, nowMs: Long): Session? {
        if (session.createdAt <= 0L || session.createdAt > nowMs ||
            nowMs - session.createdAt > MAX_AGE_MS ||
            session.first.host == session.second.host
        ) return null
        return session
    }

    fun acceptsThird(
        session: Session,
        third: BrowserResearchComparison.SourceEvidence,
        nowMs: Long,
    ): Boolean {
        if (fresh(session, nowMs) == null || third.capturedAt > nowMs) return false
        if (third.host in session.hosts) return false
        if (third.finalUrl == session.first.finalUrl ||
            third.finalUrl == session.second.finalUrl ||
            third.contentSha256 == session.first.contentSha256 ||
            third.contentSha256 == session.second.contentSha256
        ) return false
        return true
    }

    fun resolve(
        session: Session,
        third: BrowserResearchComparison.SourceEvidence,
        nowMs: Long,
    ): Result? {
        if (!acceptsThird(session, third, nowMs)) return null

        val firstToThird = BrowserResearchComparison.compare(
            BrowserResearchComparison.Session(
                taskId = session.taskId,
                query = session.query,
                first = session.first,
                createdAt = session.createdAt,
            ),
            third,
            nowMs,
        ) ?: return null
        val secondToThird = BrowserResearchComparison.compare(
            BrowserResearchComparison.Session(
                taskId = session.taskId,
                query = session.query,
                first = session.second,
                createdAt = session.createdAt,
            ),
            third,
            nowMs,
        ) ?: return null

        val pairs = listOf(firstToThird, secondToThird)
        val conflictObserved =
            session.reason == Reason.CRITICAL_LITERAL_CONFLICT ||
                pairs.any {
                    it.claimAssessment.relation ==
                        BrowserResearchComparison.ClaimRelation.CRITICAL_LITERAL_CONFLICT
                }
        val exactGoalMatchedSupport = pairs.any {
            it.claimAssessment.relation ==
                BrowserResearchComparison.ClaimRelation.EXACT_SAFE_STATEMENT_MATCH &&
                it.sharedTerms.isNotEmpty()
        }
        val disposition = when {
            conflictObserved -> Disposition.CONFLICT_REMAINS_AFTER_THIRD
            exactGoalMatchedSupport -> Disposition.BOUNDED_SUMMARY_READY_AFTER_THIRD
            else -> Disposition.FINAL_UNRESOLVED_NO_ALIGNMENT
        }
        return Result(
            taskId = session.taskId,
            query = session.query,
            first = session.first,
            second = session.second,
            third = third,
            firstToThird = firstToThird,
            secondToThird = secondToThird,
            disposition = disposition,
            boundedSummaryReady =
                disposition == Disposition.BOUNDED_SUMMARY_READY_AFTER_THIRD,
        )
    }
}
