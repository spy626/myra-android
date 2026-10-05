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
}
