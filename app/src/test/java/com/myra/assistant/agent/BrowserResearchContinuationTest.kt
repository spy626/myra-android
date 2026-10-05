package com.myra.assistant.agent

import org.junit.Assert.*
import org.junit.Test

class BrowserResearchContinuationTest {
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

    @Test fun readyTwoSourceResultCannotStartThirdSourceContinuation() {
        val sentence =
            "Security updates describe important validation improvements for public Android users."
        val compared = comparison(
            source("https://one.example/security", "one.example", 'a',
                sentence, listOf("security", "updates"), 2_000L),
            source("https://two.example/security", "two.example", 'b',
                sentence, listOf("security", "updates"), 2_200L),
        )
        val goal = BrowserResearchGoalCompletion.assess(compared)
        assertEquals(
            BrowserResearchGoalCompletion.Disposition.BOUNDED_SUMMARY_READY,
            goal.disposition)
        assertNull(BrowserResearchContinuation.start(compared, goal, 2_400L))
    }

    @Test fun unresolvedNoAlignmentMayHoldOneExplicitThirdSourceWindow() {
        val compared = comparison(
            source("https://one.example/security", "one.example", 'c',
                "Security updates describe important validation improvements for public Android users.",
                listOf("security", "updates"), 2_000L),
            source("https://two.example/bulletin", "two.example", 'd',
                "Android patch bulletins cover platform hardening changes and remediation guidance.",
                listOf("android", "security"), 2_200L),
        )
        val goal = BrowserResearchGoalCompletion.assess(compared)
        val continuation = requireNotNull(
            BrowserResearchContinuation.start(compared, goal, 2_400L))
        assertEquals(
            BrowserResearchContinuation.Reason.NO_SAFE_CLAIM_ALIGNMENT,
            continuation.reason)
        assertEquals(setOf("one.example", "two.example"), continuation.hosts)
        assertFalse(continuation.claimKey.isBlank())
        assertNotNull(BrowserResearchContinuation.fresh(continuation, 2_500L))
        assertNull(BrowserResearchContinuation.fresh(
            continuation, 2_400L + BrowserResearchContinuation.MAX_AGE_MS + 1L))
    }

    @Test fun thirdSourceMustUseNewHostAndNewEvidence() {
        val compared = comparison(
            source("https://one.example/security", "one.example", 'e',
                "Security updates describe important validation improvements for public Android users.",
                listOf("security", "updates"), 2_000L),
            source("https://two.example/bulletin", "two.example", 'f',
                "Android patch bulletins cover platform hardening changes and remediation guidance.",
                listOf("android", "security"), 2_200L),
        )
        val continuation = requireNotNull(BrowserResearchContinuation.start(
            compared, BrowserResearchGoalCompletion.assess(compared), 2_400L))
        val sameHost = source("https://two.example/third", "two.example", '1',
            "Security bulletin details additional Android platform remediation information.",
            listOf("security", "android"), 2_500L)
        assertFalse(BrowserResearchContinuation.acceptsThird(
            continuation, sameHost, 2_600L))

        val newHost = source("https://three.example/security", "three.example", '2',
            "Security bulletin details additional Android platform remediation information.",
            listOf("security", "android"), 2_500L)
        assertTrue(BrowserResearchContinuation.acceptsThird(
            continuation, newHost, 2_600L))
    }

    @Test fun thirdExactMatchCanMakeNoAlignmentCaseBoundedSummaryReady() {
        val firstSentence =
            "Security updates describe important validation improvements for public Android users."
        val compared = comparison(
            source("https://one.example/security", "one.example", '3',
                firstSentence, listOf("security", "updates"), 2_000L),
            source("https://two.example/bulletin", "two.example", '4',
                "Android patch bulletins cover platform hardening changes and remediation guidance.",
                listOf("android", "security"), 2_200L),
        )
        val continuation = requireNotNull(BrowserResearchContinuation.start(
            compared, BrowserResearchGoalCompletion.assess(compared), 2_400L))
        val third = source("https://three.example/security", "three.example", '5',
            firstSentence, listOf("security", "updates"), 2_500L)
        val result = requireNotNull(
            BrowserResearchContinuation.resolve(continuation, third, 2_600L))
        assertEquals(
            BrowserResearchContinuation.Disposition.BOUNDED_SUMMARY_READY_AFTER_THIRD,
            result.disposition)
        assertTrue(result.boundedSummaryReady)
        assertFalse(result.factualTruthVerified)
        assertFalse(result.autonomousContinuationAllowed)
    }

    @Test fun criticalLiteralConflictRemainsUnresolvedAfterThirdSource() {
        val compared = comparison(
            source("https://one.example/release", "one.example", '6',
                "Security update version 4.2 shipped to supported Android devices in 2026.",
                listOf("security", "update", "android"), 2_000L),
            source("https://two.example/release", "two.example", '7',
                "Security update version 4.3 shipped to supported Android devices in 2025.",
                listOf("security", "update", "android"), 2_200L),
        )
        val continuation = requireNotNull(BrowserResearchContinuation.start(
            compared, BrowserResearchGoalCompletion.assess(compared), 2_400L))
        val third = source("https://three.example/release", "three.example", '8',
            "Security update version 4.2 shipped to supported Android devices in 2026.",
            listOf("security", "update", "android"), 2_500L)
        val result = requireNotNull(
            BrowserResearchContinuation.resolve(continuation, third, 2_600L))
        assertEquals(
            BrowserResearchContinuation.Disposition.CONFLICT_REMAINS_AFTER_THIRD,
            result.disposition)
        assertFalse(result.boundedSummaryReady)
        assertFalse(result.factualTruthVerified)
        assertFalse(result.autonomousContinuationAllowed)
    }

    @Test fun thirdNoAlignmentEndsTheBoundedContinuationWithoutFourthSource() {
        val compared = comparison(
            source("https://one.example/security", "one.example", '9',
                "Security updates describe important validation improvements for public Android users.",
                listOf("security", "updates"), 2_000L),
            source("https://two.example/bulletin", "two.example", 'a',
                "Android patch bulletins cover platform hardening changes and remediation guidance.",
                listOf("android", "security"), 2_200L),
        )
        val continuation = requireNotNull(BrowserResearchContinuation.start(
            compared, BrowserResearchGoalCompletion.assess(compared), 2_400L))
        val third = source("https://three.example/advisory", "three.example", 'b',
            "Public advisory documentation describes device maintenance recommendations.",
            listOf("advisory", "maintenance"), 2_500L)
        val result = requireNotNull(
            BrowserResearchContinuation.resolve(continuation, third, 2_600L))
        assertEquals(
            BrowserResearchContinuation.Disposition.FINAL_UNRESOLVED_NO_ALIGNMENT,
            result.disposition)
        assertFalse(result.boundedSummaryReady)
        assertFalse(result.autonomousContinuationAllowed)
    }
    @Test fun workingTaskOwnerTransitionsComparisonIntoExactlyOnceThirdSourceClaim() {
        var now = 2_400L
        val store = WorkingTaskContextStore(now = { now })
        val compared = comparison(
            source("https://one.example/security", "one.example", 'c',
                "Security updates describe important validation improvements for public Android users.",
                listOf("security", "updates"), 2_000L),
            source("https://two.example/bulletin", "two.example", 'd',
                "Android patch bulletins cover platform hardening changes and remediation guidance.",
                listOf("android", "security"), 2_200L),
        )
        val comparisonSession = BrowserResearchComparison.Session(
            taskId = compared.taskId,
            query = compared.query,
            first = compared.first,
            createdAt = 2_100L,
        )
        assertTrue(store.beginResearchComparison(comparisonSession))
        assertTrue(store.claimResearchComparison(comparisonSession))
        val continuation = requireNotNull(BrowserResearchContinuation.start(
            compared, BrowserResearchGoalCompletion.assess(compared), now))
        assertTrue(store.completeResearchComparison(comparisonSession, continuation))
        assertEquals(continuation, store.pendingResearchContinuation())

        assertTrue(store.claimResearchContinuation(continuation))
        assertNull(store.pendingResearchContinuation())
        assertFalse(store.claimResearchContinuation(continuation))
        store.releaseResearchContinuation(continuation)
        assertEquals(continuation, store.pendingResearchContinuation())
        assertTrue(store.claimResearchContinuation(continuation))
        assertTrue(store.completeResearchContinuation(continuation))
        assertNull(store.pendingResearchContinuation())
    }

    @Test fun newSearchDropsPendingThirdSourceContinuation() {
        val store = WorkingTaskContextStore(now = { 2_400L })
        val compared = comparison(
            source("https://one.example/security", "one.example", 'e',
                "Security updates describe important validation improvements for public Android users.",
                listOf("security", "updates"), 2_000L),
            source("https://two.example/bulletin", "two.example", 'f',
                "Android patch bulletins cover platform hardening changes and remediation guidance.",
                listOf("android", "security"), 2_200L),
        )
        val comparisonSession = BrowserResearchComparison.Session(
            taskId = compared.taskId,
            query = compared.query,
            first = compared.first,
            createdAt = 2_100L,
        )
        assertTrue(store.beginResearchComparison(comparisonSession))
        assertTrue(store.claimResearchComparison(comparisonSession))
        val continuation = requireNotNull(BrowserResearchContinuation.start(
            compared, BrowserResearchGoalCompletion.assess(compared), 2_400L))
        assertTrue(store.completeResearchComparison(comparisonSession, continuation))
        assertNotNull(store.pendingResearchContinuation())

        store.beginSearch(
            "different topic",
            SearchDestination.BROWSER,
            ToolCapability.BROWSER_SEARCH.name,
            "results visible",
        )
        assertNull(store.pendingResearchContinuation())
    }

    @Test fun thirdStructuredLiteralAnchorSupportCanResolveNoAlignmentCase() {
        val compared = comparison(
            source("https://one.example/release", "one.example", 'c',
                "Security update version 4.2 shipped to supported Android devices in 2026.",
                listOf("security", "update", "android"), 2_000L),
            source("https://two.example/bulletin", "two.example", 'd',
                "Android patch bulletin explains remediation guidance for platform maintainers.",
                listOf("android", "security"), 2_200L),
        )
        assertEquals(
            BrowserResearchComparison.ClaimRelation.NO_CLAIM_ALIGNMENT,
            compared.claimAssessment.relation)
        val continuation = requireNotNull(BrowserResearchContinuation.start(
            compared, BrowserResearchGoalCompletion.assess(compared), 2_400L))
        val third = source(
            "https://three.example/release", "three.example", 'e',
            "Supported Android devices received security release 4.2 during 2026.",
            listOf("security", "update", "android"), 2_500L)
        val result = requireNotNull(
            BrowserResearchContinuation.resolve(continuation, third, 2_600L))
        assertEquals(
            BrowserResearchComparison.ClaimRelation.STRUCTURED_LITERAL_ANCHOR_SUPPORT,
            result.firstToThird.claimAssessment.relation)
        assertEquals(
            BrowserResearchContinuation.Disposition.BOUNDED_SUMMARY_READY_AFTER_THIRD,
            result.disposition)
        assertTrue(result.boundedSummaryReady)
        assertFalse(result.factualTruthVerified)
        assertFalse(result.autonomousContinuationAllowed)
    }

    @Test fun paraphrasedLiteralConflictCannotBeOverruledByThirdSourceSupport() {
        val compared = comparison(
            source("https://one.example/release", "one.example", '1',
                "Security update version 4.2 shipped to supported Android devices in 2026.",
                listOf("security", "update", "android"), 2_000L),
            source("https://two.example/release", "two.example", '2',
                "Supported Android devices received security release 4.3 during 2025.",
                listOf("security", "update", "android"), 2_200L),
        )
        assertEquals(
            BrowserResearchComparison.ClaimRelation.CRITICAL_LITERAL_CONFLICT,
            compared.claimAssessment.relation)
        val continuation = requireNotNull(BrowserResearchContinuation.start(
            compared, BrowserResearchGoalCompletion.assess(compared), 2_400L))
        val third = source(
            "https://three.example/release", "three.example", '3',
            "Security update version 4.2 shipped to supported Android devices in 2026.",
            listOf("security", "update", "android"), 2_500L)

        val result = requireNotNull(
            BrowserResearchContinuation.resolve(continuation, third, 2_600L))

        assertEquals(
            BrowserResearchContinuation.Disposition.CONFLICT_REMAINS_AFTER_THIRD,
            result.disposition)
        assertFalse(result.boundedSummaryReady)
    }

    @Test fun oppositeActionConflictCannotBeOverruledByThirdSourceSupport() {
        val compared = comparison(
            source("https://one.example/release", "one.example", '4',
                "Android security update 4.2 adds camera support to Pixel devices in 2026.",
                listOf("security", "update", "android"), 2_000L),
            source("https://two.example/release", "two.example", '5',
                "Android security update 4.2 removes camera support from Pixel devices in 2026.",
                listOf("security", "update", "android"), 2_200L),
        )
        assertEquals(
            BrowserResearchComparison.ClaimRelation.STRUCTURED_CLAIM_CONFLICT,
            compared.claimAssessment.relation)
        val continuation = requireNotNull(BrowserResearchContinuation.start(
            compared, BrowserResearchGoalCompletion.assess(compared), 2_400L))
        assertEquals(
            BrowserResearchContinuation.Reason.STRUCTURED_CLAIM_CONFLICT,
            continuation.reason)
        val third = source(
            "https://three.example/release", "three.example", '6',
            "Android security update 4.2 adds camera support to Pixel devices in 2026.",
            listOf("security", "update", "android"), 2_500L)

        val result = requireNotNull(
            BrowserResearchContinuation.resolve(continuation, third, 2_600L))

        assertEquals(
            BrowserResearchContinuation.Disposition.CONFLICT_REMAINS_AFTER_THIRD,
            result.disposition)
        assertFalse(result.boundedSummaryReady)
    }

}
