package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceResearchQueryPlanTest {
    @Test fun comparativeResearchCoversBothSubjectsWithBoundedQueries() {
        val question = "Android mein WorkManager aur Foreground Service mein kya difference hai? Reliable sources ke saath explain karo."
        val original = WorkspaceWebLinkIntent.Request(
            "WorkManager Foreground Service", maxResults = 3)
        val queries = WorkspaceResearchQueryPlan.queries(question, original)
        assertEquals(3, queries.size)
        assertTrue(queries[0].query.contains("WorkManager"))
        assertTrue(queries[1].query.contains("Foreground Service"))
        assertEquals(original, queries[2])
        assertTrue(queries.all { it.preferredHost == null })
    }

    @Test fun mixedLanguageBatteryResearchSearchesBothSidesBeforeBroadQuery() {
        val question = "Android aur iOS ki battery optimization compare karo. " +
            "Multiple reliable websites se information lekar sources ke saath answer do."
        val original = WorkspaceWebLinkIntent.Request(question, maxResults = 3)
        val queries = WorkspaceResearchQueryPlan.queries(question, original)
        assertEquals(3, queries.size)
        assertEquals("Android battery optimization", queries[0].query)
        assertEquals("iOS battery optimization", queries[1].query)
        assertEquals(original, queries[2])
    }

    @Test fun researchSourceSelectionRequiresBothTopicsWhenAvailable() {
        val question = "Compare WorkManager and Foreground Service"
        val original = WorkspaceWebLinkIntent.Request(
            "WorkManager Foreground Service", maxResults = 3)
        fun v(title: String, path: String) =
            WorkspacePublicWebSearch.Verified(title,
                "https://developer.android.com/" + path, "", "text/html")
        val work = v("Android WorkManager official documentation", "workmanager")
        val work2 = v("WorkManager work constraints", "workmanager/constraints")
        val service = v("Foreground Service official guide", "foreground-services")
        val chosen = WorkspaceResearchQueryPlan.choose(
            question, original, listOf(work, work2, service))
        assertEquals(3, chosen.size)
        assertTrue(chosen.contains(work))
        assertTrue(chosen.contains(service))
    }

    @Test fun plainLinkRequestsRetainExistingOneSearchRouting() {
        val single = WorkspaceWebLinkIntent.Request("AIRI repo", "github.com", 1)
        assertEquals(listOf(single),
            WorkspaceResearchQueryPlan.queries("Compare AIRI and something", single))
    }
}
