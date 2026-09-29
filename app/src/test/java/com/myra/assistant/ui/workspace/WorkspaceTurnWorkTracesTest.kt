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
