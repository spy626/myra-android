package com.myra.assistant.agent

import org.junit.Assert.*
import org.junit.Test

class BrowserResearchAnswerSynthesisTest {
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

    private fun comparison(
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

    @Test fun twoSourceReadyAnswerUsesOnlyExactObservedStatementAndKeepsTruthFalse() {
        val statement =
            "Security updates describe important validation improvements for public Android users."
        val compared = comparison(
            source("https://one.example/security", "one.example", 'a',
                statement, listOf("security", "updates"), 2_000L),
            source("https://two.example/security", "two.example", 'b',
                statement, listOf("security", "updates"), 2_200L),
        )
        val goal = BrowserResearchGoalCompletion.assess(compared)
        val answer = requireNotNull(BrowserResearchAnswerSynthesis.fromTwo(compared, goal))

        assertEquals("android security updates", answer.query)
        assertEquals(statement, answer.evidenceStatement)
        assertEquals(listOf("one.example", "two.example"), answer.supportingHosts)
        assertEquals(2, answer.supportingUrls.size)
        assertEquals(2, answer.observedUrls.size)
        assertEquals(2, answer.evidenceSourceCount)
        assertFalse(answer.factualTruthVerified)
        assertFalse(answer.organizationalIndependenceVerified)
        assertFalse(answer.providerShared)
        assertFalse(answer.memoryWritten)
        assertFalse(answer.autonomousContinuationAllowed)
    }

    @Test fun unresolvedTwoSourceComparisonCannotFabricateAnswer() {
        val compared = comparison(
            source("https://one.example/security", "one.example", 'c',
                "Security updates describe important validation improvements for public Android users.",
                listOf("security", "updates"), 2_000L),
            source("https://two.example/bulletin", "two.example", 'd',
                "Android patch bulletins cover platform hardening changes and remediation guidance.",
                listOf("android", "security"), 2_200L),
        )
        val goal = BrowserResearchGoalCompletion.assess(compared)
        assertFalse(goal.boundedSummaryReady)
        assertNull(BrowserResearchAnswerSynthesis.fromTwo(compared, goal))
    }

    @Test fun threeSourceReadyAnswerUsesTheExactSupportingPairButRecordsAllObservedSources() {
        val statement =
            "Security updates describe important validation improvements for public Android users."
        val compared = comparison(
            source("https://one.example/security", "one.example", 'e',
                statement, listOf("security", "updates"), 2_000L),
            source("https://two.example/bulletin", "two.example", 'f',
                "Android patch bulletins cover platform hardening changes and remediation guidance.",
                listOf("android", "security"), 2_200L),
        )
        val continuation = requireNotNull(BrowserResearchContinuation.start(
            compared,
            BrowserResearchGoalCompletion.assess(compared),
            2_400L,
        ))
        val third = source(
            "https://three.example/security", "three.example", '1',
            statement, listOf("security", "updates"), 2_500L)
        val resolved = requireNotNull(
            BrowserResearchContinuation.resolve(continuation, third, 2_600L))
        val answer = requireNotNull(BrowserResearchAnswerSynthesis.fromThree(resolved))

        assertEquals(
            BrowserResearchContinuation.Disposition.BOUNDED_SUMMARY_READY_AFTER_THIRD,
            resolved.disposition)
        assertEquals(statement, answer.evidenceStatement)
        assertEquals(listOf("one.example", "three.example"), answer.supportingHosts)
        assertEquals(2, answer.supportingUrls.size)
        assertEquals(3, answer.observedUrls.size)
        assertEquals(3, answer.evidenceSourceCount)
        assertFalse(answer.factualTruthVerified)
        assertFalse(answer.organizationalIndependenceVerified)
        assertFalse(answer.providerShared)
        assertFalse(answer.memoryWritten)
        assertFalse(answer.autonomousContinuationAllowed)
    }

    @Test fun unresolvedThirdSourceResultCannotProduceFinalAnswer() {
        val compared = comparison(
            source("https://one.example/release", "one.example", '2',
                "Security update version 4.2 shipped to supported Android devices in 2026.",
                listOf("security", "update", "android"), 2_000L),
            source("https://two.example/release", "two.example", '3',
                "Security update version 4.3 shipped to supported Android devices in 2025.",
                listOf("security", "update", "android"), 2_200L),
        )
        val continuation = requireNotNull(BrowserResearchContinuation.start(
            compared,
            BrowserResearchGoalCompletion.assess(compared),
            2_400L,
        ))
        val third = source(
            "https://three.example/release", "three.example", '4',
            "Security update version 4.2 shipped to supported Android devices in 2026.",
            listOf("security", "update", "android"), 2_500L)
        val resolved = requireNotNull(
            BrowserResearchContinuation.resolve(continuation, third, 2_600L))

        assertEquals(
            BrowserResearchContinuation.Disposition.CONFLICT_REMAINS_AFTER_THIRD,
            resolved.disposition)
        assertNull(BrowserResearchAnswerSynthesis.fromThree(resolved))
    }
    @Test fun structuredTwoSourceAnswerReportsObservedStructureNotParaphraseTruth() {
        val compared = comparison(
            source("https://one.example/release", "one.example", '5',
                "Security update version 4.2 shipped to supported Android devices in 2026.",
                listOf("security", "update", "android"), 2_000L),
            source("https://two.example/release", "two.example", '6',
                "Supported Android devices received security release 4.2 during 2026.",
                listOf("security", "update", "android"), 2_200L),
        )
        val goal = BrowserResearchGoalCompletion.assess(compared)
        val answer = requireNotNull(BrowserResearchAnswerSynthesis.fromTwo(compared, goal))

        assertEquals(
            BrowserResearchAnswerSynthesis.SupportKind.STRUCTURED_LITERAL_ANCHOR,
            answer.supportKind)
        assertEquals(listOf("4.2", "2026"), answer.criticalLiterals)
        assertTrue(answer.sharedAnchors.contains("security"))
        assertTrue(answer.sharedAnchors.contains("android"))
        assertTrue(answer.sharedAnchors.contains("supported"))
        assertEquals(2, answer.supportingExcerpts.size)
        assertTrue(answer.evidenceStatement.contains("Shared lexical anchors"))
        assertTrue(answer.evidenceStatement.contains("Matching critical literals"))
        assertFalse(answer.evidenceStatement.contains("same meaning", ignoreCase = true))
        assertFalse(answer.factualTruthVerified)
        assertFalse(answer.organizationalIndependenceVerified)
        assertFalse(answer.providerShared)
        assertFalse(answer.memoryWritten)
        assertFalse(answer.autonomousContinuationAllowed)
    }

}
