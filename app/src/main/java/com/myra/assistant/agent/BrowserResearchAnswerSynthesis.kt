package com.myra.assistant.agent

/**
 * Deterministic local-only answer synthesis from already accepted bounded research evidence.
 *
 * This does not paraphrase, rank sources, infer truth, call a model/provider, write memory,
 * or grant further navigation. The answer statement must already be an exact safe statement
 * match accepted by BrowserResearchComparison.
 */
internal object BrowserResearchAnswerSynthesis {
    data class Answer(
        val taskId: String,
        val query: String,
        val evidenceStatement: String,
        val supportingUrls: List<String>,
        val supportingHosts: List<String>,
        val observedUrls: List<String>,
        val evidenceSourceCount: Int,
        val factualTruthVerified: Boolean = false,
        val organizationalIndependenceVerified: Boolean = false,
        val providerShared: Boolean = false,
        val memoryWritten: Boolean = false,
        val autonomousContinuationAllowed: Boolean = false,
    )

    fun fromTwo(
        result: BrowserResearchComparison.Result,
        goal: BrowserResearchGoalCompletion.Assessment,
    ): Answer? {
        if (goal.disposition != BrowserResearchGoalCompletion.Disposition.BOUNDED_SUMMARY_READY ||
            !goal.boundedSummaryReady || goal.factualTruthVerified ||
            goal.autonomousContinuationAllowed ||
            result.claimAssessment.relation !=
                BrowserResearchComparison.ClaimRelation.EXACT_SAFE_STATEMENT_MATCH ||
            result.sharedTerms.isEmpty()
        ) return null
        return exactPairAnswer(
            taskId = result.taskId,
            query = result.query,
            pair = result,
            observedUrls = listOf(result.first.finalUrl, result.second.finalUrl),
            evidenceSourceCount = 2,
        )
    }

    fun fromThree(
        result: BrowserResearchContinuation.Result,
    ): Answer? {
        if (result.disposition !=
            BrowserResearchContinuation.Disposition.BOUNDED_SUMMARY_READY_AFTER_THIRD ||
            !result.boundedSummaryReady || result.factualTruthVerified ||
            result.autonomousContinuationAllowed
        ) return null

        val supportedPair = listOf(result.firstToThird, result.secondToThird)
            .firstOrNull {
                it.claimAssessment.relation ==
                    BrowserResearchComparison.ClaimRelation.EXACT_SAFE_STATEMENT_MATCH &&
                    it.sharedTerms.isNotEmpty()
            } ?: return null

        return exactPairAnswer(
            taskId = result.taskId,
            query = result.query,
            pair = supportedPair,
            observedUrls = listOf(
                result.first.finalUrl,
                result.second.finalUrl,
                result.third.finalUrl,
            ),
            evidenceSourceCount = 3,
        )
    }

    private fun exactPairAnswer(
        taskId: String,
        query: String,
        pair: BrowserResearchComparison.Result,
        observedUrls: List<String>,
        evidenceSourceCount: Int,
    ): Answer? {
        val claim = pair.claimAssessment
        if (claim.relation !=
            BrowserResearchComparison.ClaimRelation.EXACT_SAFE_STATEMENT_MATCH
        ) return null
        val statement = claim.firstExcerpt?.trim().orEmpty()
        val matchingStatement = claim.secondExcerpt?.trim().orEmpty()
        if (taskId.isBlank() || query.isBlank() ||
            statement.length !in 36..200 || matchingStatement.length !in 36..200 ||
            pair.first.host == pair.second.host ||
            pair.first.finalUrl == pair.second.finalUrl ||
            evidenceSourceCount !in 2..3
        ) return null

        val supportingUrls = listOf(pair.first.finalUrl, pair.second.finalUrl).distinct()
        val supportingHosts = listOf(pair.first.host, pair.second.host).distinct()
        val observed = observedUrls.distinct()
        if (supportingUrls.size != 2 || supportingHosts.size != 2 ||
            observed.size != evidenceSourceCount ||
            !supportingUrls.all(observed::contains)
        ) return null

        return Answer(
            taskId = taskId,
            query = query,
            evidenceStatement = statement,
            supportingUrls = supportingUrls,
            supportingHosts = supportingHosts,
            observedUrls = observed,
            evidenceSourceCount = evidenceSourceCount,
        )
    }
}
