package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceResearchProgressTest {
    private fun source(url: String): WorkspacePublicWebSearch.Verified =
        WorkspacePublicWebSearch.Verified(
            title = "Battery management support",
            finalUrl = url,
            snippet = "Background app activity and power-saving settings.",
            contentType = "text/html",
        )

    @Test fun researchBeginsWithShortHonestPlanNotInventedFindings() {
        val update = WorkspaceResearchProgress.kickoff()
        assertEquals("Thinking", update.status)
        assertTrue(update.message.contains("public sources"))
        assertFalse(update.message.contains("I found"))
        assertFalse(update.message.contains("Searched 4"))
    }

    @Test fun reportsOnlyActuallyVerifiedUniqueWebsiteDomains() {
        val list = listOf(
            source("https://developer.android.com/develop/connectivity/preserving-battery"),
            source("https://support.apple.com/en-us/108055"),
            source("https://support.apple.com/en-us/108055"),
            source("https://lifetips.alibaba.com/battery"),
        )
        val first = requireNotNull(WorkspaceResearchProgress.searchRound(0, list))
        assertEquals("research-round-0", first.key)
        assertEquals("Found 3 reachable websites", first.status)
        assertTrue(first.message.contains("developer.android.com"))
        assertTrue(first.message.contains("support.apple.com"))
        assertTrue(first.message.contains("lifetips.alibaba.com"))
        assertFalse(first.message.contains("/en-us/108055"))
        assertTrue(first.message.contains("Checking which pages"))
        assertNull(WorkspaceResearchProgress.searchRound(1, emptyList()))
    }

    @Test fun noRawQuestionKeysCredentialsOrFakeWebsiteCounts() {
        val update = requireNotNull(WorkspaceResearchProgress.searchRound(
            3, listOf(source("https://support.apple.com/en-us/108055"))))
        assertEquals("Found 1 reachable website", update.status)
        assertFalse(update.message.contains("108055"))
        assertFalse(update.message.contains("api_key"))
        assertEquals("research-round-3", update.key)
    }

    @Test fun readProgressNeverEquatesReachablePagesWithFactualEvidence() {
        assertNull(WorkspaceResearchProgress.readEvidence(0, 0))
        val none = requireNotNull(WorkspaceResearchProgress.readEvidence(3, 0))
        assertEquals("Reading sources completed", none.status)
        assertTrue(none.message.contains("did not provide suitable evidence"))
        val found = requireNotNull(WorkspaceResearchProgress.readEvidence(5, 2))
        assertEquals("Read 2 evidence-bearing pages", found.status)
        assertTrue(found.message.contains("2 of 5 checked pages"))
        assertTrue(runCatching {
            WorkspaceResearchProgress.readEvidence(1, 2)
        }.isFailure)
    }

    @Test fun publicTimelineKeepsCompletedResearchMilestonesExpandable() {
        val store = WorkspaceTurnPublicNarrations()
        store.reset("turn-7")
        val plan = WorkspaceResearchProgress.kickoff()
        store.upsert("turn-7", plan.key, plan.status, plan.message)
        val found = requireNotNull(WorkspaceResearchProgress.searchRound(0, listOf(
            source("https://developer.android.com/about"),
            source("https://support.apple.com/en-us/108055"),
        )))
        store.upsert("turn-7", found.key, found.status, found.message)
        val read = requireNotNull(WorkspaceResearchProgress.readEvidence(2, 1))
        store.upsert("turn-7", read.key, read.status, read.message)
        val trace = WorkspaceWorkSnapshot(
            events = listOf(
                WorkspaceWorkEvent(WorkspaceWorkPhase.THINKING, "Thinking", null, 1L),
                WorkspaceWorkEvent(WorkspaceWorkPhase.DONE, "Research completed", null, 5_001L),
            ),
            startedAtMs = 1L, endedAtMs = 5_001L,
        )
        assertTrue(WorkspaceWorkPresentation.visibleEvents(trace, false).isEmpty())
        assertEquals("Worked for 5s \u203a",
            WorkspaceWorkPresentation.compactRow(trace, false, 9_000L))
        val expanded = WorkspaceWorkConversationTimeline.completed(
            trace, store.forTurn("turn-7"))
        assertEquals(3, expanded.filterIsInstance<WorkspaceWorkConversationItem.Public>().size)
        assertTrue(expanded.any {
            it is WorkspaceWorkConversationItem.Public &&
                it.message.statusLabel == "Found 2 reachable websites"
        })
    }
}
