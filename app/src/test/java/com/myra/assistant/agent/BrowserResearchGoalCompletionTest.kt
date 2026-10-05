package com.myra.assistant.agent

import org.junit.Assert.*
import org.junit.Test

class BrowserResearchGoalCompletionTest {
    private fun source(
        url: String,
        host: String,
        sha: Char,
        excerpt: String,
        terms: List<String>,
        at: Long,
    ) = requireNotNull(BrowserResearchComparison.source(
        finalUrl = url,
        host = host,
        contentSha256 = sha.toString().repeat(64),
        excerpts = listOf(excerpt),
        matchedTerms = terms,
        capturedAt = at,
    ))

    private fun result(
        first: BrowserResearchComparison.SourceEvidence,
        second: BrowserResearchComparison.SourceEvidence,
    ): BrowserResearchComparison.Result {
        val session = BrowserResearchComparison.Session(
            taskId = "research-1",
            query = "android security updates",
            first = first,
            createdAt = 2_100L,
        )
        return requireNotNull(BrowserResearchComparison.compare(session, second, 2_300L))
    }

    @Test fun exactSafeStatementWithSharedQueryEvidenceMakesOnlyBoundedSummaryReady() {
        val sentence =
            "Security updates describe important validation improvements for public Android users."
        val compared = result(
            source("https://one.example/security", "one.example", 'a',
                sentence, listOf("security", "updates"), 2_000L),
            source("https://two.example/security", "two.example", 'b',
                sentence, listOf("security", "updates"), 2_200L),
        )
        val assessment = BrowserResearchGoalCompletion.assess(compared)
        assertEquals(
            BrowserResearchGoalCompletion.Disposition.BOUNDED_SUMMARY_READY,
            assessment.disposition)
        assertTrue(assessment.boundedSummaryReady)
        assertFalse(assessment.factualTruthVerified)
        assertFalse(assessment.autonomousContinuationAllowed)
        assertTrue(assessment.observedOutcome.contains("truth_unverified"))
    }

    @Test fun criticalLiteralConflictStaysUnresolvedAndNeverSelectsTruth() {
        val compared = result(
            source("https://one.example/release", "one.example", 'c',
                "Security update version 4.2 shipped to supported Android devices in 2026.",
                listOf("security", "update", "android"), 2_000L),
            source("https://two.example/release", "two.example", 'd',
                "Security update version 4.3 shipped to supported Android devices in 2025.",
                listOf("security", "update", "android"), 2_200L),
        )
        val assessment = BrowserResearchGoalCompletion.assess(compared)
        assertEquals(
            BrowserResearchGoalCompletion.Disposition.UNRESOLVED_CRITICAL_LITERAL_CONFLICT,
            assessment.disposition)
        assertFalse(assessment.boundedSummaryReady)
        assertFalse(assessment.factualTruthVerified)
        assertFalse(assessment.autonomousContinuationAllowed)
        assertTrue(assessment.observedOutcome.contains("research_unresolved"))
    }

    @Test fun differentWordingNeedsMoreEvidenceWithoutPretendingParaphraseAgreement() {
        val compared = result(
            source("https://one.example/security", "one.example", 'e',
                "Security updates describe important validation improvements for public Android users.",
                listOf("security", "updates"), 2_000L),
            source("https://two.example/bulletin", "two.example", 'f',
                "Android patch bulletins cover platform hardening changes and remediation guidance.",
                listOf("android", "security"), 2_200L),
        )
        val assessment = BrowserResearchGoalCompletion.assess(compared)
        assertEquals(
            BrowserResearchGoalCompletion.Disposition.MORE_EVIDENCE_REQUIRED,
            assessment.disposition)
        assertFalse(assessment.boundedSummaryReady)
        assertFalse(assessment.factualTruthVerified)
        assertFalse(assessment.autonomousContinuationAllowed)
    }

    @Test fun exactTextWithoutSharedQueryTermsDoesNotBecomeReady() {
        val sentence =
            "Security updates describe important validation improvements for public Android users."
        val compared = result(
            source("https://one.example/security", "one.example", '1',
                sentence, listOf("updates"), 2_000L),
            source("https://two.example/security", "two.example", '2',
                sentence, listOf("security"), 2_200L),
        )
        assertEquals(
            BrowserResearchComparison.ClaimRelation.EXACT_SAFE_STATEMENT_MATCH,
            compared.claimAssessment.relation)
        assertTrue(compared.sharedTerms.isEmpty())
        val assessment = BrowserResearchGoalCompletion.assess(compared)
        assertEquals(
            BrowserResearchGoalCompletion.Disposition.MORE_EVIDENCE_REQUIRED,
            assessment.disposition)
        assertFalse(assessment.boundedSummaryReady)
    }
    @Test fun structuredLiteralAnchorSupportCanMakeBoundedSummaryReadyWithStrongQueryOverlap() {
        val compared = result(
            source("https://one.example/release", "one.example", '3',
                "Security update version 4.2 shipped to supported Android devices in 2026.",
                listOf("security", "update", "android"), 2_000L),
            source("https://two.example/release", "two.example", '4',
                "Supported Android devices received security release 4.2 during 2026.",
                listOf("security", "update", "android"), 2_200L),
        )
        assertEquals(
            BrowserResearchComparison.ClaimRelation.STRUCTURED_LITERAL_ANCHOR_SUPPORT,
            compared.claimAssessment.relation)
        assertTrue(BrowserResearchGoalCompletion.supportsBoundedSummary(compared))
        val assessment = BrowserResearchGoalCompletion.assess(compared)
        assertEquals(
            BrowserResearchGoalCompletion.Disposition.BOUNDED_SUMMARY_READY,
            assessment.disposition)
        assertTrue(assessment.boundedSummaryReady)
        assertTrue(assessment.observedOutcome.contains("structured_literal_anchor_support"))
        assertFalse(assessment.factualTruthVerified)
        assertFalse(assessment.autonomousContinuationAllowed)
    }

    @Test fun structuredSupportWithOnlyOneSharedQueryTermStillNeedsMoreEvidence() {
        val compared = result(
            source("https://one.example/release", "one.example", '5',
                "Security update version 4.2 shipped to supported Android devices in 2026.",
                listOf("security", "update", "android"), 2_000L),
            source("https://two.example/release", "two.example", '6',
                "Supported Android devices received security release 4.2 during 2026.",
                listOf("security", "bulletin"), 2_200L),
        )
        assertEquals(
            BrowserResearchComparison.ClaimRelation.STRUCTURED_LITERAL_ANCHOR_SUPPORT,
            compared.claimAssessment.relation)
        assertEquals(listOf("security"), compared.sharedTerms)
        assertFalse(BrowserResearchGoalCompletion.supportsBoundedSummary(compared))
        val assessment = BrowserResearchGoalCompletion.assess(compared)
        assertEquals(
            BrowserResearchGoalCompletion.Disposition.MORE_EVIDENCE_REQUIRED,
            assessment.disposition)
        assertFalse(assessment.boundedSummaryReady)
    }

    @Test fun exactTextWithOnlyOneSharedQueryTermStillNeedsMoreEvidence() {
        val sentence =
            "Security updates describe important validation improvements for public Android users."
        val compared = result(
            source("https://one.example/security", "one.example", '7',
                sentence, listOf("security", "updates"), 2_000L),
            source("https://two.example/security", "two.example", '8',
                sentence, listOf("security", "android"), 2_200L),
        )
        assertEquals(
            BrowserResearchComparison.ClaimRelation.EXACT_SAFE_STATEMENT_MATCH,
            compared.claimAssessment.relation)
        assertEquals(listOf("security"), compared.sharedTerms)
        val assessment = BrowserResearchGoalCompletion.assess(compared)
        assertEquals(
            BrowserResearchGoalCompletion.Disposition.MORE_EVIDENCE_REQUIRED,
            assessment.disposition)
        assertFalse(assessment.boundedSummaryReady)
    }

    @Test fun exactTextMustMatchTheQueryInsideTheSupportingSentence() {
        val shared =
            "Security guidance remains identical across these public documentation pages."
        val compared = result(
            requireNotNull(BrowserResearchComparison.source(
                finalUrl = "https://one.example/security",
                host = "one.example",
                contentSha256 = "9".repeat(64),
                excerpts = listOf(
                    shared,
                    "Android updates include additional maintenance notes for supported devices.",
                ),
                matchedTerms = listOf("security", "android", "updates"),
                capturedAt = 2_000L,
            )),
            requireNotNull(BrowserResearchComparison.source(
                finalUrl = "https://two.example/security",
                host = "two.example",
                contentSha256 = "a".repeat(64),
                excerpts = listOf(
                    shared,
                    "Android updates include separate release notes for supported devices.",
                ),
                matchedTerms = listOf("security", "android", "updates"),
                capturedAt = 2_200L,
            )),
        )
        assertEquals(
            BrowserResearchComparison.ClaimRelation.EXACT_SAFE_STATEMENT_MATCH,
            compared.claimAssessment.relation)
        assertTrue(compared.sharedTerms.size >= 2)

        val assessment = BrowserResearchGoalCompletion.assess(compared)

        assertEquals(
            BrowserResearchGoalCompletion.Disposition.MORE_EVIDENCE_REQUIRED,
            assessment.disposition)
        assertFalse(assessment.boundedSummaryReady)
    }

}
