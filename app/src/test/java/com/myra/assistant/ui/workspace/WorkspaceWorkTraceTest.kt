package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWorkTraceTest {
    @Test fun traceShowsOnlyObservableBoundedEventsAndDuration() {
        var now = 1_000L
        val trace = WorkspaceWorkTrace { now }

        trace.begin(WorkspaceWorkPhase.CODING, "Building website", "xKiro Free")
        now += 2_000L
        trace.add(WorkspaceWorkPhase.VERIFYING, "Verifying website files",
            "index.html · style.css · script.js")
        now += 3_000L
        trace.finishSuccess("Website verified")

        val snapshot = trace.snapshot()
        assertEquals(3, snapshot.events.size)
        assertEquals(WorkspaceWorkPhase.DONE, snapshot.current?.phase)
        assertEquals("Worked for 5s", snapshot.compactLabel(now))
        assertFalse(snapshot.active)
    }

    @Test fun duplicateStageDoesNotSpamTimeline() {
        val trace = WorkspaceWorkTrace { 10L }
        trace.begin(WorkspaceWorkPhase.CODING, "Editing style.css", "xKiro Free")
        trace.add(WorkspaceWorkPhase.CODING, "Editing style.css", "xKiro Free")
        assertEquals(1, trace.snapshot().events.size)
    }

    @Test fun secretsAreRedactedBeforeDisplay() {
        val trace = WorkspaceWorkTrace { 10L }
        trace.begin(WorkspaceWorkPhase.ERROR, "Provider failed",
            "Authorization: Bearer abcdefghijklmnop api_key=sk-super-secret-123456")
        val detail = trace.snapshot().current?.detail.orEmpty()
        assertFalse(detail.contains("abcdefghijklmnop"))
        assertFalse(detail.contains("sk-super-secret"))
        assertTrue(detail.contains("[redacted]"))
    }

    @Test fun terminalTraceStartsFreshOnNextTask() {
        var now = 100L
        val trace = WorkspaceWorkTrace { now }
        trace.begin(WorkspaceWorkPhase.THINKING, "Thinking")
        now += 100L
        trace.finishError("Reply failed")
        now += 100L
        trace.add(WorkspaceWorkPhase.CODING, "Editing index.html", "Z.ai GLM-4.7-Flash")

        val snapshot = trace.snapshot()
        assertEquals(1, snapshot.events.size)
        assertEquals(WorkspaceWorkPhase.CODING, snapshot.current?.phase)
        assertTrue(snapshot.active)
    }
}
