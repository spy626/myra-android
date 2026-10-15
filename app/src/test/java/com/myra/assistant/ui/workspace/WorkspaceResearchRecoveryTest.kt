package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for phone test 1000065143.mp4: a reachable Alibaba page was the only
 * readable publisher, so LYRA stopped instead of discovering Apple/Google evidence.
 */
class WorkspaceResearchRecoveryTest {
    private val question =
        "LYRA, Android aur iOS ki battery optimization compare karo. " +
            "Multiple reliable websites se information lekar sources ke saath answer do."
    private val original = WorkspaceWebLinkIntent.Request(
        "Android iOS battery optimization", maxResults = 3)
    private val initial = WorkspaceResearchQueryPlan.queries(question, original)

    private fun extract(title: String, url: String, text: String) =
        WorkspacePublicResearch.Extract(WorkspacePublicWebSearch.Verified(
            title, url, "battery optimization", "text/html"), listOf(text))

    private val blog = extract("Android vs iOS battery optimization",
        "https://lifetips.alibaba.com/battery-optimization",
        "Android battery optimization and iOS battery optimization both manage energy usage.")
    private val google = extract("Android battery optimization",
        "https://support.google.com/android/answer/7664692",
        "Android battery optimization limits background energy consumption.")
    private val apple = extract("iOS battery optimization on iPhone",
        "https://support.apple.com/en-us/101604",
        "iOS battery optimization reduces background energy consumption.")

    @Test fun aSingleReadableBlogTriggersRecoveryAfterInitialThreeSearches() {
        assertTrue(WorkspaceResearchRecovery.needsRecovery(question, listOf(blog)))
        val first = requireNotNull(WorkspaceResearchRecovery.next(
            question, original, listOf(blog), initial))
        assertEquals("developer.android.com", first.preferredHost)
        assertTrue(first.query.contains("Android"))
        assertTrue(first.query.contains("battery"))
    }

    @Test fun afterGoogleIsReadRecoveryTargetsMissingApplePublisher() {
        val evidence = listOf(blog, google)
        val tried = initial + requireNotNull(WorkspaceResearchRecovery.next(
            question, original, listOf(blog), initial))
        val next = requireNotNull(WorkspaceResearchRecovery.next(
            question, original, evidence, tried))
        assertEquals("support.apple.com", next.preferredHost)
        assertTrue(next.query.contains("iOS"))
    }

    @Test fun independentPrimarySourcesEndResearchInsteadOfSearchingForever() {
        val evidence = listOf(google, apple)
        assertFalse(WorkspaceResearchRecovery.needsRecovery(question, evidence))
        assertNull(WorkspaceResearchRecovery.next(question, original, evidence, initial))
    }

    @Test fun genericComparisonWithOfficialDocumentedSubjectsDoesNotRequireExtraRound() {
        val q = "Compare WorkManager and Foreground Service"
        val request = WorkspaceWebLinkIntent.Request("WorkManager Foreground Service", maxResults = 3)
        val evidence = listOf(
            extract("WorkManager official guide",
                "https://developer.android.com/topic/libraries/architecture/workmanager",
                "WorkManager is designed for persistent deferrable background work."),
            extract("Foreground Service official documentation",
                "https://developer.android.com/develop/background-work/services/fgs",
                "A foreground service is suitable for immediate user-noticeable ongoing work."),
        )
        assertFalse(WorkspaceResearchRecovery.needsRecovery(q, evidence))
        assertNull(WorkspaceResearchRecovery.next(
            q, request, evidence, WorkspaceResearchQueryPlan.queries(q, request)))
    }

    @Test fun readOnlyDirectLinkAndPinnedHostRoutesNeverEnterResearchRecovery() {
        assertNull(WorkspaceResearchRecovery.next(question,
            WorkspaceWebLinkIntent.Request("iOS battery link", maxResults = 1),
            listOf(blog), emptyList()))
        assertNull(WorkspaceResearchRecovery.next(question,
            WorkspaceWebLinkIntent.Request("ios battery", "support.apple.com", 3),
            listOf(blog), emptyList()))
    }

    @Test fun previousQueriesAreNotRepeatedAndCanUseBroaderWebsiteFallback() {
        val first = requireNotNull(WorkspaceResearchRecovery.next(
            question, original, listOf(blog), initial))
        val tried = initial + first
        val second = requireNotNull(WorkspaceResearchRecovery.next(
            question, original, listOf(blog), tried))
        assertTrue(first.query != second.query || first.preferredHost != second.preferredHost)
        val third = requireNotNull(WorkspaceResearchRecovery.next(
            question, original, listOf(blog), tried + second))
        assertNull(third.preferredHost)
        assertTrue(third.query.contains("support"))
    }

    @Test fun recoveryIsBoundedToFourAdditionalQueriesAndNotAnotherModelOwner() {
        assertEquals(4, WorkspaceResearchRecovery.MAX_EXTRA_SEARCHES)
        val instructions = WorkspaceIntelligentUnderstanding.SYSTEM_INSTRUCTIONS
        assertTrue(instructions.contains("actual", ignoreCase = true))
        assertNotNull(WorkspaceResearchRecovery.next(question, original, listOf(blog), initial))
    }
}
