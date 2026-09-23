package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

    @Test fun completeSseAccumulatesVisibleTextAndIgnoresReasoning() {
        val request = Request.Builder().url(WorkspaceZaiFree.ENDPOINT).build()
        val body = (
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hidden\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\" bro!\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: [DONE]\n\n"
        ).toResponseBody("text/event-stream".toMediaType())
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("ok").body(body).build()
        val seen = mutableListOf<String>()
        val final = WorkspaceZaiStream.read(response) { seen += it }
        assertEquals("Hi bro!", final)
        assertEquals(listOf("Hi", "Hi bro!"), seen)
        assertFalse(final.contains("hidden"))
    }

    @Test fun truncatedSseNeverReturnsPartialAsDurableReply() {
        val request = Request.Builder().url(WorkspaceZaiFree.ENDPOINT).build()
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n"
            .toResponseBody("text/event-stream".toMediaType())
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("ok").body(body).build()
        val seen = mutableListOf<String>()
        assertTrue(runCatching { WorkspaceZaiStream.read(response) { seen += it } }.isFailure)
        assertEquals(listOf("partial"), seen)
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
