package com.myra.assistant.agent

/**
 * Deterministic local-only answer synthesis from already accepted bounded research evidence.
 *
 * This does not paraphrase, rank sources, infer truth, call a model/provider, write memory,
 * or grant further navigation. Support must already be accepted by the conservative
 * BrowserResearchGoalCompletion policy: exact text, or identical critical literals plus
 * strong lexical anchors and query overlap.
 */
internal object BrowserResearchAnswerSynthesis {
    enum class SupportKind {
        EXACT_TEXT,
        STRUCTURED_LITERAL_ANCHOR,
    }

    data class Answer(
        val taskId: String,
        val query: String,
        val supportKind: SupportKind,
        val evidenceStatement: String,
        val supportingExcerpts: List<String>,
        val sharedAnchors: List<String>,
        val criticalLiterals: List<String>,
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
            !BrowserResearchGoalCompletion.supportsBoundedSummary(result)
        ) return null
        return supportedPairAnswer(
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
            .firstOrNull(BrowserResearchGoalCompletion::supportsBoundedSummary)
            ?: return null

        return supportedPairAnswer(
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

    private fun supportedPairAnswer(
        taskId: String,
        query: String,
        pair: BrowserResearchComparison.Result,
        observedUrls: List<String>,
        evidenceSourceCount: Int,
    ): Answer? {
        val claim = pair.claimAssessment
        if (!BrowserResearchGoalCompletion.supportsBoundedSummary(pair)) return null
        val statement = claim.firstExcerpt?.trim().orEmpty()
        val matchingStatement = claim.secondExcerpt?.trim().orEmpty()
        if (taskId.isBlank() || query.isBlank() ||
            statement.length !in 36..200 || matchingStatement.length !in 36..200 ||
            pair.first.host == pair.second.host ||
            pair.first.finalUrl == pair.second.finalUrl ||
            evidenceSourceCount !in 2..3
        ) return null

        val supportKind = when (claim.relation) {
            BrowserResearchComparison.ClaimRelation.EXACT_SAFE_STATEMENT_MATCH ->
                SupportKind.EXACT_TEXT
            BrowserResearchComparison.ClaimRelation.STRUCTURED_LITERAL_ANCHOR_SUPPORT ->
                SupportKind.STRUCTURED_LITERAL_ANCHOR
            else -> return null
        }
        val anchors = if (supportKind == SupportKind.STRUCTURED_LITERAL_ANCHOR)
            claim.sharedAnchors else emptyList()
        val literals = if (supportKind == SupportKind.STRUCTURED_LITERAL_ANCHOR)
            claim.firstLiterals else emptyList()
        if (supportKind == SupportKind.STRUCTURED_LITERAL_ANCHOR &&
            (anchors.size < 3 || literals.isEmpty() ||
                claim.firstLiterals.toSet() != claim.secondLiterals.toSet())
        ) return null
        val evidenceStatement =
            if (supportKind == SupportKind.EXACT_TEXT) statement
            else "Shared lexical anchors: " + anchors.joinToString(", ") +
                ". Matching critical literals: " + literals.joinToString(", ") + "."
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
            supportKind = supportKind,
            evidenceStatement = evidenceStatement,
            supportingExcerpts = listOf(statement, matchingStatement).distinct(),
            sharedAnchors = anchors,
            criticalLiterals = literals,
            supportingUrls = supportingUrls,
            supportingHosts = supportingHosts,
            observedUrls = observed,
            evidenceSourceCount = evidenceSourceCount,
        )
    }
}
