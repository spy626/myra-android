package com.myra.assistant.agent

/**
 * Conservative completion semantics for one verified two-source browser comparison.
 *
 * "Ready" means only that LYRA has enough bounded, local evidence to present a
 * two-source summary. It NEVER means factual truth, source-organization independence,
 * permission to navigate again, provider sharing, or memory persistence.
 */
internal object BrowserResearchGoalCompletion {
    enum class Disposition {
        BOUNDED_SUMMARY_READY,
        UNRESOLVED_CRITICAL_LITERAL_CONFLICT,
        MORE_EVIDENCE_REQUIRED,
    }

    data class Assessment(
        val disposition: Disposition,
        val boundedSummaryReady: Boolean,
        val factualTruthVerified: Boolean = false,
        val autonomousContinuationAllowed: Boolean = false,
        val observedOutcome: String,
    )

    fun supportsBoundedSummary(result: BrowserResearchComparison.Result): Boolean =
        when (result.claimAssessment.relation) {
            BrowserResearchComparison.ClaimRelation.EXACT_SAFE_STATEMENT_MATCH ->
                result.sharedTerms.size >= 2
            BrowserResearchComparison.ClaimRelation.STRUCTURED_LITERAL_ANCHOR_SUPPORT ->
                result.sharedTerms.size >= 2 &&
                    result.claimAssessment.sharedAnchors.size >= 3 &&
                    result.claimAssessment.firstLiterals.isNotEmpty() &&
                    result.claimAssessment.firstLiterals.toSet() ==
                        result.claimAssessment.secondLiterals.toSet()
            else -> false
        }

    fun assess(result: BrowserResearchComparison.Result): Assessment {
        val relation = result.claimAssessment.relation
        return when {
            relation == BrowserResearchComparison.ClaimRelation.CRITICAL_LITERAL_CONFLICT ||
                relation == BrowserResearchComparison.ClaimRelation.STRUCTURED_CLAIM_CONFLICT ->
                Assessment(
                    disposition = Disposition.UNRESOLVED_CRITICAL_LITERAL_CONFLICT,
                    boundedSummaryReady = false,
                    observedOutcome =
                        if (relation ==
                            BrowserResearchComparison.ClaimRelation.CRITICAL_LITERAL_CONFLICT
                        ) {
                            "two_public_hosts_critical_literal_conflict_research_unresolved"
                        } else {
                            "two_public_hosts_structured_claim_conflict_research_unresolved"
                        },
                )

            supportsBoundedSummary(result) ->
                Assessment(
                    disposition = Disposition.BOUNDED_SUMMARY_READY,
                    boundedSummaryReady = true,
                    observedOutcome =
                        if (relation ==
                            BrowserResearchComparison.ClaimRelation.EXACT_SAFE_STATEMENT_MATCH
                        ) {
                            "two_public_hosts_exact_safe_statement_support_bounded_summary_ready_truth_unverified"
                        } else {
                            "two_public_hosts_structured_literal_anchor_support_bounded_summary_ready_truth_unverified"
                        },
                )

            else ->
                Assessment(
                    disposition = Disposition.MORE_EVIDENCE_REQUIRED,
                    boundedSummaryReady = false,
                    observedOutcome =
                        "two_public_hosts_no_safe_claim_alignment_more_evidence_required",
                )
        }
    }
}
