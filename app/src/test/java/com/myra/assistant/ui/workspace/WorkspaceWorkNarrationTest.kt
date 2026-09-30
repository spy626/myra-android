package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWorkNarrationTest {
    @Test fun rawTechnicalTraceIsPreservedButNormalNarrationIsHumanReadable() {
        val raw = WorkspaceWorkSnapshot(
            events = listOf(
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.THINKING,
                    "Execution plan locked",
                    "3 files · exact CI required",
                    1L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "Task budget",
                    "provider 1/5 · review 0/3 · commit 0/2",
                    2L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.CODING,
                    "Preparing bounded GitHub edit",
                    "xKiro · qwen/qwen3-coder-plus:free · 3 files",
                    3L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "Second-provider QA review",
                    "Groq · openai/gpt-oss-120b · read-only",
                    4L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "CI #4000 in_progress",
                    "abcdef123456",
                    5L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "CI #4000 GREEN",
                    "abcdef123456",
                    6L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.DONE,
                    "GitHub self-edit CI verified",
                    "CI #4000 · abcdef123456",
                    7L,
                ),
            ),
            startedAtMs = 1L,
            endedAtMs = 7L,
        )

        val visible = WorkspaceWorkNarration.events(raw)

        assertEquals(7, raw.events.size)
        assertEquals(
            listOf(
                "Analyzing the task",
                "Applying the requested change",
                "Reviewing the change",
                "Verifying exact CI",
                "GitHub change verified",
            ),
            visible.map { it.label },
        )
        val rendered = visible.joinToString(" ") { it.label + " " + it.detail.orEmpty() }
        assertFalse(rendered.contains("provider 1/5"))
        assertFalse(rendered.contains("qwen"))
        assertFalse(rendered.contains("gpt-oss"))
        assertFalse(rendered.contains("abcdef123456"))
        assertFalse(rendered.contains("Task budget"))
    }

    @Test fun recoveryAndErrorStayMeaningfulWithoutInventingSuccess() {
        val snapshot = WorkspaceWorkSnapshot(
            events = listOf(
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.RECOVERING,
                    "Resuming saved GitHub coding checkpoint",
                    "repair_pre_commit · abc",
                    1L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.RECOVERING,
                    "Reading failed commit for one bounded repair",
                    "CI #4001",
                    2L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.ERROR,
                    "GitHub self-edit stopped",
                    "Reviewer rejected the proposed patch before commit.",
                    3L,
                ),
            ),
            startedAtMs = 1L,
            endedAtMs = 3L,
        )

        val visible = WorkspaceWorkNarration.events(snapshot)

        assertEquals(
            listOf("Resuming the task", "Repairing the task", "Work stopped"),
            visible.map { it.label },
        )
        assertTrue(visible.last().detail.orEmpty().contains("Reviewer rejected"))
        assertFalse(visible.any { it.label.contains("verified", ignoreCase = true) })
    }

    @Test fun evidenceGroundedUpdateKeepsItsSituationSpecificMeaning() {
        val snapshot = WorkspaceWorkSnapshot(
            events = listOf(
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "Review found a fixable issue",
                    "Revision repeated the rejected proposal instead of fixing the guard.",
                    1L,
                    WorkspaceWorkPresentationKind.EVIDENCE,
                ),
            ),
            startedAtMs = 1L,
            endedAtMs = null,
        )

        val visible = WorkspaceWorkNarration.events(snapshot)

        assertEquals(1, visible.size)
        assertEquals("Review found a fixable issue", visible.single().label)
        assertTrue(visible.single().detail.orEmpty().contains("repeated the rejected proposal"))
    }

    @Test fun repeatedGenericReadingRowsAreSuppressedAcrossEvidenceUpdates() {
        val snapshot = WorkspaceWorkSnapshot(
            events = listOf(
                WorkspaceWorkEvent(WorkspaceWorkPhase.READING, "Refreshing GitHub read access", null, 1L),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.THINKING,
                    "Scoped work to A.kt",
                    "Only the selected file is in this bounded change.",
                    2L,
                    WorkspaceWorkPresentationKind.EVIDENCE,
                ),
                WorkspaceWorkEvent(WorkspaceWorkPhase.READING, "Reading bounded related source set", null, 3L),
            ),
            startedAtMs = 1L,
            endedAtMs = null,
        )

        val visible = WorkspaceWorkNarration.events(snapshot)

        assertEquals(1, visible.count { it.label == "Reading relevant context" })
        assertTrue(visible.any { it.label == "Scoped work to A.kt" })
    }

    @Test fun exactCiEvidenceSuppressesGenericCiAndBookkeepingRows() {
        val snapshot = WorkspaceWorkSnapshot(
            events = listOf(
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "Waiting for exact GitHub Actions result",
                    "sha",
                    1L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "CI #3302 is in progress",
                    "Waiting for this exact commit to finish.",
                    2L,
                    WorkspaceWorkPresentationKind.EVIDENCE,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "Updating draft PR",
                    "No merge",
                    3L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "Completion criteria satisfied",
                    "Selected scope + exact CI GREEN verified",
                    4L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.THINKING,
                    "Preparing result explanation",
                    "Verified task evidence only",
                    5L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "CI #3302 passed for this commit",
                    "Configured build/tests completed successfully.",
                    6L,
                    WorkspaceWorkPresentationKind.EVIDENCE,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.DONE,
                    "GitHub self-edit CI verified",
                    "CI #3302",
                    7L,
                ),
            ),
            startedAtMs = 1L,
            endedAtMs = null,
        )

        val visible = WorkspaceWorkNarration.events(snapshot)

        assertEquals(
            listOf(
                "CI #3302 is in progress",
                "CI #3302 passed for this commit",
                "GitHub change verified",
            ),
            visible.map { it.label },
        )
        assertFalse(visible.any { it.label == "Analyzing the task" })
        assertFalse(visible.any { it.label == "Updating the draft PR" })
        assertFalse(visible.any { it.label == "Confirming completion" })
        assertFalse(visible.any { it.label == "Verifying exact CI" })
    }

    @Test fun repeatedTechnicalStatusesCollapseIntoOneHumanMilestone() {
        val snapshot = WorkspaceWorkSnapshot(
            events = listOf(
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "Waiting for exact Actions run",
                    null,
                    1L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "CI #9 in_progress",
                    "sha",
                    2L,
                ),
                WorkspaceWorkEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "CI #9 GREEN",
                    "sha",
                    3L,
                ),
            ),
            startedAtMs = 1L,
            endedAtMs = null,
        )

        val visible = WorkspaceWorkNarration.events(snapshot)

        assertEquals(1, visible.size)
        assertEquals("Verifying exact CI", visible.single().label)
        assertEquals(1L, visible.single().atMs)
    }
}
