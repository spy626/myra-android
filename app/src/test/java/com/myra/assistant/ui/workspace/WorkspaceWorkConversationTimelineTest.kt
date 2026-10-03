package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWorkConversationTimelineTest {
    @Test fun activeTimelineKeepsPublicHistoryAndOneLiveCurrentStatus() {
        val snapshot = WorkspaceWorkSnapshot(
            events = listOf(
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.READING,
                    "Reading bounded related source set",
                    null,
                    1_000L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "CI #3316 is in progress",
                    "Waiting for this exact commit to finish.",
                    4_000L,
                    WorkspaceWorkPresentationKind.EVIDENCE,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "Waiting for exact Actions run",
                    null,
                    5_000L,
                ),
            ),
            startedAtMs = 1_000L,
            endedAtMs = null,
        )
        val public = listOf(
            WorkspacePublicWorkMessage(
                key = "scope",
                statusLabel = "Scoped work to ReadingTrackerSafetyTest.kt",
                text = "Scope clear.",
                atMs = 2_000L,
            ),
            WorkspacePublicWorkMessage(
                key = "ci-running-3316",
                statusLabel = "CI #3316 is in progress",
                text = "CI is running.",
                atMs = 4_100L,
            ),
        )

        val items = WorkspaceWorkConversationTimeline.active(snapshot, public)

        assertEquals(2, items.size)
        assertTrue(items[0] is WorkspaceWorkConversationItem.Public)
        val current = items[1] as WorkspaceWorkConversationItem.Public
        assertNotNull(current.liveEvent)
        assertEquals("CI #3316 is in progress", current.liveEvent?.label)
    }

    @Test fun activeTimelineUsesPublicMilestoneAsLiveRowWhenItMatchesCurrentStatus() {
        val current = WorkspaceWorkEvent(
            WorkspaceWorkPhase.VERIFYING,
            "CI #3316 is in progress",
            "Waiting for this exact commit to finish.",
            4_000L,
            WorkspaceWorkPresentationKind.EVIDENCE,
        )
        val snapshot = WorkspaceWorkSnapshot(
            events = listOf(current),
            startedAtMs = 4_000L,
            endedAtMs = null,
        )
        val public = listOf(
            WorkspacePublicWorkMessage(
                key = "ci-running-3316",
                statusLabel = "CI #3316 is in progress",
                text = "CI is running.",
                atMs = 4_100L,
            )
        )

        val item = WorkspaceWorkConversationTimeline.active(snapshot, public).single()
            as WorkspaceWorkConversationItem.Public

        assertNotNull(item.liveEvent)
        assertEquals("CI #3316 is in progress", item.liveEvent?.label)
        assertEquals("Waiting for this exact commit to finish.", item.liveEvent?.detail)
        val display = WorkspaceWorkConversationTimeline.liveEventForDisplay(item)
        assertEquals("CI #3316 is in progress", display?.label)
        assertEquals(null, display?.detail)
    }

    @Test fun completedTimelineInterleavesByTimeAndSuppressesCoveredDuplicateRows() {
        val snapshot = WorkspaceWorkSnapshot(
            events = listOf(
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.READING,
                    "Reading bounded related source set",
                    null,
                    1_000L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.CODING,
                    "Prepared a change proposal",
                    "1 file changed",
                    3_000L,
                    WorkspaceWorkPresentationKind.EVIDENCE,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "Second-provider QA review",
                    null,
                    4_000L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "Review accepted the proposed change",
                    "Scoped correctly.",
                    5_000L,
                    WorkspaceWorkPresentationKind.EVIDENCE,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.DONE,
                    "GitHub self-edit CI verified",
                    "CI #3316",
                    9_000L,
                ),
            ),
            startedAtMs = 1_000L,
            endedAtMs = 9_000L,
        )
        val public = listOf(
            WorkspacePublicWorkMessage(
                "proposal",
                "Prepared a change proposal",
                "Proposal ready.",
                3_100L,
            ),
            WorkspacePublicWorkMessage(
                "review-first-accept",
                "Review accepted the proposed change",
                "Review clear.",
                5_100L,
            ),
        )

        val items = WorkspaceWorkConversationTimeline.completed(snapshot, public)
        val labels = items.map {
            when (it) {
                is WorkspaceWorkConversationItem.Public -> it.message.statusLabel
                is WorkspaceWorkConversationItem.Work -> it.event.label
            }
        }

        assertEquals(1, labels.count { it == "Prepared a change proposal" })
        assertEquals(1, labels.count { it == "Review accepted the proposed change" })
        assertTrue(labels.indexOf("Reading relevant context") < labels.indexOf("Prepared a change proposal"))
        assertTrue(labels.indexOf("Prepared a change proposal") < labels.indexOf("Review accepted the proposed change"))
        assertTrue(labels.last() == "GitHub change verified")
        assertFalse(items.filterIsInstance<WorkspaceWorkConversationItem.Work>().any {
            it.event.label == "Review accepted the proposed change"
        })
    }
}
