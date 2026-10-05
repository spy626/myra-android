package com.myra.assistant.agent

import org.junit.Assert.*
import org.junit.Test

class BrowserResearchComparisonTest {
    private fun handoff() = BrowserResearchSourceHandoff.Pending(
        taskId = "research-1",
        query = "android security updates",
        completedAt = 1_000L,
    )

    private fun source(
        url: String,
        host: String,
        sha: Char,
        terms: List<String> = listOf("security", "updates"),
        at: Long = 2_000L,
    ) = requireNotNull(BrowserResearchComparison.source(
        finalUrl = url,
        host = host,
        contentSha256 = sha.toString().repeat(64),
        excerpts = listOf(
            "Security updates describe important validation improvements for public Android users."),
        matchedTerms = terms,
        capturedAt = at,
    ))

    @Test fun sessionIsEphemeralFreshAndRequiresFilteredFirstEvidence() {
        val first = source("https://one.example/security", "one.example", 'a')
        val session = requireNotNull(
            BrowserResearchComparison.start(handoff(), first, 2_100L))
        assertEquals("research-1", session.taskId)
        assertNotNull(BrowserResearchComparison.fresh(session, 2_200L))
        assertNull(BrowserResearchComparison.fresh(
            session, 2_100L + BrowserResearchComparison.MAX_AGE_MS + 1L))

        assertNull(BrowserResearchComparison.source(
            finalUrl = "https://one.example/security",
            host = "one.example",
            contentSha256 = "b".repeat(64),
            excerpts = listOf("short"),
            matchedTerms = listOf("security"),
            capturedAt = 2_000L,
        ))
    }

    @Test fun onlyDifferentHostCanBecomeIndependentSecondSource() {
        val first = source("https://one.example/security", "one.example", 'a')
        val session = requireNotNull(
            BrowserResearchComparison.start(handoff(), first, 2_100L))
        val sameHost = source(
            "https://one.example/other", "one.example", 'b', at = 2_200L)
        assertNull(BrowserResearchComparison.compare(session, sameHost, 2_300L))

        val second = source(
            "https://two.example/bulletin", "two.example", 'c',
            terms = listOf("security", "android"), at = 2_200L)
        val result = requireNotNull(
            BrowserResearchComparison.compare(session, second, 2_300L))
        assertEquals(
            BrowserResearchComparison.Decision.TWO_INDEPENDENT_SOURCES_VERIFIED,
            result.decision)
        assertEquals(listOf("security"), result.sharedTerms)
        assertEquals(listOf("updates"), result.firstOnlyTerms)
        assertEquals(listOf("android"), result.secondOnlyTerms)
    }

    @Test fun workingTaskOwnerClaimsSecondSourceOnceAndCanReleaseFailedRead() {
        var now = 2_100L
        val store = WorkingTaskContextStore(now = { now })
        val first = source("https://one.example/security", "one.example", 'a')
        val session = requireNotNull(
            BrowserResearchComparison.start(handoff(), first, now))
        assertTrue(store.beginResearchComparison(session))
        assertEquals(session, store.pendingResearchComparison())

        assertTrue(store.claimResearchComparison(session))
        assertNull(store.pendingResearchComparison())
        assertFalse(store.claimResearchComparison(session))

        store.releaseResearchComparison(session)
        assertEquals(session, store.pendingResearchComparison())
        assertTrue(store.claimResearchComparison(session))
        assertTrue(store.completeResearchComparison(session))
        assertNull(store.pendingResearchComparison())
    }

    @Test fun newSearchOrTaskClearDropsOldComparisonWindow() {
        val store = WorkingTaskContextStore(now = { 2_100L })
        val session = requireNotNull(BrowserResearchComparison.start(
            handoff(),
            source("https://one.example/security", "one.example", 'a'),
            2_100L,
        ))
        assertTrue(store.beginResearchComparison(session))
        store.beginSearch(
            "different topic", SearchDestination.BROWSER,
            ToolCapability.BROWSER_SEARCH.name, "results visible")
        assertNull(store.pendingResearchComparison())

        assertTrue(store.beginResearchComparison(session))
        store.clearTask()
        assertNull(store.pendingResearchComparison())
    }
    @Test fun exactSafeStatementMatchIsObservedWithoutCallingItTruth() {
        val first = source(
            "https://one.example/security", "one.example", 'a',
            terms = listOf("security", "updates"))
        val session = requireNotNull(
            BrowserResearchComparison.start(handoff(), first, 2_100L))
        val second = requireNotNull(BrowserResearchComparison.source(
            finalUrl = "https://two.example/security",
            host = "two.example",
            contentSha256 = "b".repeat(64),
            excerpts = listOf(
                "Security updates describe important validation improvements for public Android users."),
            matchedTerms = listOf("security", "updates"),
            capturedAt = 2_200L,
        ))
        val result = requireNotNull(
            BrowserResearchComparison.compare(session, second, 2_300L))
        assertEquals(
            BrowserResearchComparison.ClaimRelation.EXACT_SAFE_STATEMENT_MATCH,
            result.claimAssessment.relation)
        assertNotNull(result.claimAssessment.firstExcerpt)
        assertNotNull(result.claimAssessment.secondExcerpt)
    }

    @Test fun sameStatementShapeWithDifferentCriticalLiteralIsConflictNotTruthSelection() {
        val first = requireNotNull(BrowserResearchComparison.source(
            finalUrl = "https://one.example/release",
            host = "one.example",
            contentSha256 = "c".repeat(64),
            excerpts = listOf(
                "Security update version 4.2 shipped to supported Android devices in 2026."),
            matchedTerms = listOf("security", "update", "android"),
            capturedAt = 2_000L,
        ))
        val session = requireNotNull(
            BrowserResearchComparison.start(handoff(), first, 2_100L))
        val second = requireNotNull(BrowserResearchComparison.source(
            finalUrl = "https://two.example/release",
            host = "two.example",
            contentSha256 = "d".repeat(64),
            excerpts = listOf(
                "Security update version 4.3 shipped to supported Android devices in 2025."),
            matchedTerms = listOf("security", "update", "android"),
            capturedAt = 2_200L,
        ))
        val result = requireNotNull(
            BrowserResearchComparison.compare(session, second, 2_300L))
        assertEquals(
            BrowserResearchComparison.ClaimRelation.CRITICAL_LITERAL_CONFLICT,
            result.claimAssessment.relation)
        assertEquals(listOf("4.2", "2026"), result.claimAssessment.firstLiterals)
        assertEquals(listOf("4.3", "2025"), result.claimAssessment.secondLiterals)
    }

    @Test fun differentWordingDoesNotPretendToBeParaphraseAgreement() {
        val first = source("https://one.example/security", "one.example", 'a')
        val session = requireNotNull(
            BrowserResearchComparison.start(handoff(), first, 2_100L))
        val second = requireNotNull(BrowserResearchComparison.source(
            finalUrl = "https://two.example/bulletin",
            host = "two.example",
            contentSha256 = "e".repeat(64),
            excerpts = listOf(
                "Android patch bulletins cover platform hardening changes and remediation guidance."),
            matchedTerms = listOf("android", "security"),
            capturedAt = 2_200L,
        ))
        val result = requireNotNull(
            BrowserResearchComparison.compare(session, second, 2_300L))
        assertEquals(
            BrowserResearchComparison.ClaimRelation.NO_CLAIM_ALIGNMENT,
            result.claimAssessment.relation)
    }

    @Test fun differentWordingWithSameCriticalLiteralsAndThreeAnchorsGetsStructuredSupport() {
        val first = requireNotNull(BrowserResearchComparison.source(
            finalUrl = "https://one.example/release",
            host = "one.example",
            contentSha256 = "f".repeat(64),
            excerpts = listOf(
                "Security update version 4.2 shipped to supported Android devices in 2026."),
            matchedTerms = listOf("security", "update", "android"),
            capturedAt = 2_000L,
        ))
        val session = requireNotNull(
            BrowserResearchComparison.start(handoff(), first, 2_100L))
        val second = requireNotNull(BrowserResearchComparison.source(
            finalUrl = "https://two.example/release",
            host = "two.example",
            contentSha256 = "1".repeat(64),
            excerpts = listOf(
                "Supported Android devices received security release 4.2 during 2026."),
            matchedTerms = listOf("security", "android", "update"),
            capturedAt = 2_200L,
        ))
        val result = requireNotNull(
            BrowserResearchComparison.compare(session, second, 2_300L))

        assertEquals(
            BrowserResearchComparison.ClaimRelation.STRUCTURED_LITERAL_ANCHOR_SUPPORT,
            result.claimAssessment.relation)
        assertEquals(listOf("4.2", "2026"), result.claimAssessment.firstLiterals)
        assertEquals(listOf("4.2", "2026"), result.claimAssessment.secondLiterals)
        assertTrue(result.claimAssessment.sharedAnchors.contains("security"))
        assertTrue(result.claimAssessment.sharedAnchors.contains("android"))
        assertTrue(result.claimAssessment.sharedAnchors.contains("supported"))
        assertTrue(BrowserResearchComparison.supportsBoundedClaim(
            result.claimAssessment.relation))
    }

    @Test fun sameLiteralsWithoutThreeSharedAnchorsDoNotPretendSemanticAgreement() {
        val first = requireNotNull(BrowserResearchComparison.source(
            finalUrl = "https://one.example/release",
            host = "one.example",
            contentSha256 = "2".repeat(64),
            excerpts = listOf(
                "Security update version 4.2 shipped to supported Android devices in 2026."),
            matchedTerms = listOf("security", "update", "android"),
            capturedAt = 2_000L,
        ))
        val session = requireNotNull(
            BrowserResearchComparison.start(handoff(), first, 2_100L))
        val second = requireNotNull(BrowserResearchComparison.source(
            finalUrl = "https://two.example/report",
            host = "two.example",
            contentSha256 = "3".repeat(64),
            excerpts = listOf(
                "Financial forecast 4.2 was published for regional planning teams during 2026."),
            matchedTerms = listOf("security", "android"),
            capturedAt = 2_200L,
        ))
        val result = requireNotNull(
            BrowserResearchComparison.compare(session, second, 2_300L))
        assertEquals(
            BrowserResearchComparison.ClaimRelation.NO_CLAIM_ALIGNMENT,
            result.claimAssessment.relation)
    }

    @Test fun differentWordingWithoutCriticalLiteralsStillNeedsMoreEvidence() {
        val first = requireNotNull(BrowserResearchComparison.source(
            finalUrl = "https://one.example/security",
            host = "one.example",
            contentSha256 = "4".repeat(64),
            excerpts = listOf(
                "Security updates improve validation for supported Android devices and public users."),
            matchedTerms = listOf("security", "updates", "android"),
            capturedAt = 2_000L,
        ))
        val session = requireNotNull(
            BrowserResearchComparison.start(handoff(), first, 2_100L))
        val second = requireNotNull(BrowserResearchComparison.source(
            finalUrl = "https://two.example/security",
            host = "two.example",
            contentSha256 = "5".repeat(64),
            excerpts = listOf(
                "Supported Android devices receive stronger security validation through platform updates."),
            matchedTerms = listOf("security", "updates", "android"),
            capturedAt = 2_200L,
        ))
        val result = requireNotNull(
            BrowserResearchComparison.compare(session, second, 2_300L))
        assertEquals(
            BrowserResearchComparison.ClaimRelation.NO_CLAIM_ALIGNMENT,
            result.claimAssessment.relation)
    }

}
