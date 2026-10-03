package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTurnWorkTracesTest {
    @Test fun secondTurnDoesNotOverwriteActiveBackgroundTurn() {
        var tick = 1_000L
        val store = WorkspaceTurnWorkTraces(maxTraces = 4) {
            WorkspaceWorkTrace { tick++ }
        }

        val github = store.reset("github-turn")
        github.begin(WorkspaceWorkPhase.CODING, "Applying GitHub change")

        val foreground = store.reset("foreground-turn")
        foreground.begin(WorkspaceWorkPhase.THINKING, "Thinking")
        foreground.finishSuccess("Reply ready")

        github.add(WorkspaceWorkPhase.VERIFYING, "Waiting for exact CI")

        assertEquals("Waiting for exact CI", store.existing("github-turn")?.snapshot()?.current?.label)
        assertTrue(store.existing("github-turn")?.snapshot()?.active == true)
        assertEquals("Reply ready", store.existing("foreground-turn")?.snapshot()?.current?.label)
        assertFalse(store.existing("foreground-turn")?.snapshot()?.active == true)
    }

    @Test fun exactTurnUpdatesNeverMoveToAnotherTrace() {
        var tick = 2_000L
        val store = WorkspaceTurnWorkTraces(maxTraces = 4) {
            WorkspaceWorkTrace { tick++ }
        }
        store.reset("first").begin(WorkspaceWorkPhase.READING, "Reading")
        store.reset("second").begin(WorkspaceWorkPhase.THINKING, "Thinking")

        store.getOrCreate("first").add(WorkspaceWorkPhase.VERIFYING, "CI running")

        assertEquals("CI running", store.existing("first")?.snapshot()?.current?.label)
        assertEquals("Thinking", store.existing("second")?.snapshot()?.current?.label)
    }

    @Test fun completionReceiptAlwaysTerminalizesExactTurnAndIsIdempotent() {
        var tick = 4_000L
        val store = WorkspaceTurnWorkTraces(maxTraces = 4) {
            WorkspaceWorkTrace { tick++ }
        }
        store.reset("github-turn").begin(WorkspaceWorkPhase.VERIFYING, "CI running")

        val first = store.ensureSuccess("github-turn", "GitHub change verified")
        val eventCount = first.snapshot().events.size
        store.ensureSuccess("github-turn", "GitHub change verified")

        val snapshot = store.existing("github-turn")!!.snapshot()
        assertFalse(snapshot.active)
        assertEquals(WorkspaceWorkPhase.DONE, snapshot.current?.phase)
        assertEquals(eventCount, snapshot.events.size)
        assertTrue(
            WorkspaceWorkPresentation.compactRow(snapshot, expanded = false, nowMs = 10_000L)
                .orEmpty()
                .startsWith("Worked for ")
        )
    }

    @Test fun completionReceiptRecreatesMinimalTerminalTraceIfMemoryWasLost() {
        var tick = 5_000L
        val store = WorkspaceTurnWorkTraces(maxTraces = 4) {
            WorkspaceWorkTrace { tick++ }
        }

        store.ensureSuccess("restored-turn", "GitHub change verified")

        val snapshot = store.existing("restored-turn")!!.snapshot()
        assertFalse(snapshot.active)
        assertEquals(WorkspaceWorkPhase.DONE, snapshot.current?.phase)
        assertEquals(
            "Worked for 1s ›",
            WorkspaceWorkPresentation.compactRow(snapshot, expanded = false, nowMs = 9_000L),
        )
    }

    @Test fun completionReceiptNeverOverwritesExistingError() {
        var tick = 6_000L
        val store = WorkspaceTurnWorkTraces(maxTraces = 4) {
            WorkspaceWorkTrace { tick++ }
        }
        store.reset("failed-turn").apply {
            begin(WorkspaceWorkPhase.VERIFYING, "Reviewing")
            finishError("Work stopped", "Reviewer rejected the change")
        }

        store.ensureSuccess("failed-turn", "GitHub change verified")

        val snapshot = store.existing("failed-turn")!!.snapshot()
        assertEquals(WorkspaceWorkPhase.ERROR, snapshot.current?.phase)
        assertTrue(snapshot.current?.detail.orEmpty().contains("Reviewer rejected"))
    }

    @Test fun boundedStoreEvictsOldTerminalTraceBeforeActiveTrace() {
        var tick = 3_000L
        val store = WorkspaceTurnWorkTraces(maxTraces = 2) {
            WorkspaceWorkTrace { tick++ }
        }

        store.reset("active").begin(WorkspaceWorkPhase.VERIFYING, "CI running")
        store.reset("old").apply {
            begin(WorkspaceWorkPhase.THINKING, "Thinking")
            finishSuccess("Done")
        }
        store.reset("new").begin(WorkspaceWorkPhase.THINKING, "New work")

        assertNotNull(store.existing("active"))
        assertNull(store.existing("old"))
        assertNotNull(store.existing("new"))
        assertEquals(listOf("active", "new"), store.ids())
    }
}
