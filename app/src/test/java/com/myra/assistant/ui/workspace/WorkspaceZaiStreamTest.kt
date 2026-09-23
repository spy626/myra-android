package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceZaiStreamTest {
    @Test fun parsesVisibleDeltaAndStopWithoutExposingReasoning() {
        val visible = WorkspaceZaiStream.parseData(
            """{"choices":[{"delta":{"role":"assistant","reasoning_content":"hidden","content":"Hi bro"},"finish_reason":null}]}"""
        )
        assertEquals("Hi bro", visible.delta)
        assertEquals(null, visible.finishReason)
        assertFalse(visible.done)

        val stop = WorkspaceZaiStream.parseData(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        )
        assertEquals("", stop.delta)
        assertEquals("stop", stop.finishReason)
        assertFalse(stop.done)
    }

    @Test fun doneMarkerIsAcceptedButMalformedOrErrorEventsAreRejected() {
        assertTrue(WorkspaceZaiStream.parseData("[DONE]").done)
        assertTrue(runCatching { WorkspaceZaiStream.parseData("not-json") }.isFailure)
        assertTrue(runCatching {
            WorkspaceZaiStream.parseData("""{"error":{"message":"private upstream detail"}}""")
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceZaiStream.parseData("""{"choices":[{"delta":{"content":{"bad":true}}}]}""")
        }.isFailure)
    }
}
