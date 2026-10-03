package com.myra.assistant.ui.workspace

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceProviderDeliberationTransportTest {
    private val d = WorkspaceProviderDeliberation
    private val session = WorkspaceProviderDeliberation.Session(
        taskId = "t1",
        turnId = "u1",
        sourceRevision = "rev1",
        task = "Review a bounded coding change.",
        acceptanceCriteria = "No unrelated files change.",
    )

    @Test fun xKiroDispatchIsExactProviderAndNoRetryClient() {
        val envelope = d.proposerEnvelope(session, WorkspaceProviderRegistry.Id.XKIRO_FREE)
        val dispatch = WorkspaceProviderDeliberationTransport.prepare(
            envelope, "xkiro-key", providerEnabled = true, sourceApproved = false)
        assertEquals(WorkspaceXKiroFree.ENDPOINT, dispatch.request.url.toString())
        assertEquals(WorkspaceProviderRegistry.Id.XKIRO_FREE, dispatch.provider)
        assertFalse(dispatch.client.retryOnConnectionFailure)
        assertFalse(dispatch.client.followRedirects)
        val buffer = Buffer()
        requireNotNull(dispatch.request.body).writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        assertEquals(WorkspaceXKiroFree.MODEL, body.getString("model"))
        assertTrue(body.getJSONArray("messages").getJSONObject(0)
            .getString("content").contains("ROLE: PROPOSER"))
    }

    @Test fun zAiSourceEnvelopeRequiresCurrentIndependentSourceApproval() {
        val source = d.boundedSource("rev1", "fun greet() = \"hi\"")
        val envelope = d.proposerEnvelope(
            session, WorkspaceProviderRegistry.Id.ZAI_FREE,
            source = source, sourceApproved = true)
        assertTrue(runCatching {
            WorkspaceProviderDeliberationTransport.prepare(
                envelope, "zai-key", providerEnabled = true, sourceApproved = false)
        }.isFailure)

        val dispatch = WorkspaceProviderDeliberationTransport.prepare(
            envelope, "zai-key", providerEnabled = true, sourceApproved = true)
        assertEquals(WorkspaceZaiFree.ENDPOINT, dispatch.request.url.toString())
        assertEquals(WorkspaceProviderRegistry.Id.ZAI_FREE, dispatch.provider)
        assertFalse(dispatch.client.retryOnConnectionFailure)
    }

    @Test fun providerPermissionOffAndUnsupportedProvidersFailClosed() {
        val x = d.proposerEnvelope(session, WorkspaceProviderRegistry.Id.XKIRO_FREE)
        assertTrue(runCatching {
            WorkspaceProviderDeliberationTransport.prepare(
                x, "xkiro-key", providerEnabled = false, sourceApproved = false)
        }.isFailure)

        val open = d.proposerEnvelope(session, WorkspaceProviderRegistry.Id.OPENROUTER_FREE)
        assertTrue(runCatching {
            WorkspaceProviderDeliberationTransport.prepare(
                open, "open-key", providerEnabled = true, sourceApproved = false)
        }.isFailure)
    }
}
