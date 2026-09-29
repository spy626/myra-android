package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWorkPresentationTest {
    @Test fun activeWorkShowsNarratedMilestonesEvenWhenTerminalDetailsDefaultCollapsed() {
        val snapshot = WorkspaceWorkSnapshot(
            events = listOf(
                WorkspaceWorkEvent(WorkspaceWorkPhase.THINKING, "Execution plan locked", null, 1_000L),
                WorkspaceWorkEvent(WorkspaceWorkPhase.CODING, "Preparing bounded GitHub edit", "provider", 2_000L),
            ),
            startedAtMs = 1_000L,
            endedAtMs = null,
        )

        assertEquals(
            listOf("Analyzing the task", "Applying the requested change"),
            WorkspaceWorkPresentation.visibleEvents(snapshot, expanded = false).map { it.label },
        )
        assertNull(WorkspaceWorkPresentation.compactRow(snapshot, expanded = false, nowMs = 9_000L))
    }

    @Test fun terminalWorkDefaultsToOneCompactWorkedForRow() {
        val snapshot = WorkspaceWorkSnapshot(
            events = listOf(
                WorkspaceWorkEvent(WorkspaceWorkPhase.THINKING, "Execution plan locked", null, 1_000L),
                WorkspaceWorkEvent(WorkspaceWorkPhase.VERIFYING, "CI #12 GREEN", "sha", 230_000L),
                WorkspaceWorkEvent(WorkspaceWorkPhase.DONE, "GitHub self-edit CI verified", null, 231_000L),
            ),
            startedAtMs = 1_000L,
            endedAtMs = 231_000L,
        )

        assertTrue(WorkspaceWorkPresentation.visibleEvents(snapshot, expanded = false).isEmpty())
        assertEquals(
            "Worked for 3m 50s ›",
            WorkspaceWorkPresentation.compactRow(snapshot, expanded = false, nowMs = 999_000L),
        )
    }

    @Test fun expandingTerminalRowRevealsHumanMilestonesAndRotatesChevron() {
        val snapshot = WorkspaceWorkSnapshot(
            events = listOf(
                WorkspaceWorkEvent(WorkspaceWorkPhase.CODING, "Preparing bounded GitHub edit", "xKiro", 1_000L),
                WorkspaceWorkEvent(WorkspaceWorkPhase.VERIFYING, "Second-provider QA review", "Groq", 2_000L),
                WorkspaceWorkEvent(WorkspaceWorkPhase.DONE, "GitHub self-edit CI verified", "CI #12", 6_000L),
            ),
            startedAtMs = 1_000L,
            endedAtMs = 6_000L,
        )

        assertEquals(
            listOf("Applying the requested change", "Reviewing the change", "GitHub change verified"),
            WorkspaceWorkPresentation.visibleEvents(snapshot, expanded = true).map { it.label },
        )
        assertEquals(
            "Worked for 5s ⌄",
            WorkspaceWorkPresentation.compactRow(snapshot, expanded = true, nowMs = 99_000L),
        )
    }

    @Test fun stoppedWorkAlsoGetsACompactReceiptWithoutPretendingSuccess() {
        val snapshot = WorkspaceWorkSnapshot(
            events = listOf(
                WorkspaceWorkEvent(WorkspaceWorkPhase.READING, "Reading bounded related source set", null, 1_000L),
                WorkspaceWorkEvent(WorkspaceWorkPhase.ERROR, "GitHub self-edit stopped", "Reviewer rejected patch", 4_000L),
            ),
            startedAtMs = 1_000L,
            endedAtMs = 4_000L,
        )

        assertTrue(WorkspaceWorkPresentation.visibleEvents(snapshot, expanded = false).isEmpty())
        assertEquals(
            "Worked for 3s ›",
            WorkspaceWorkPresentation.compactRow(snapshot, expanded = false, nowMs = 8_000L),
        )
        val expanded = WorkspaceWorkPresentation.visibleEvents(snapshot, expanded = true)
        assertEquals("Work stopped", expanded.last().label)
        assertTrue(expanded.last().detail.orEmpty().contains("Reviewer rejected"))
    }
}
