package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceExactLinkIntentTest {
    private fun msg(id: String, role: String, text: String, at: Long) =
        WorkspaceConversationStore.Message(id, role, text, at)

    @Test fun directNamedYouTubeLinkBecomesReadOnlyExactLookup() {
        val request = WorkspaceExactLinkIntent.decide(
            "CarryMinati ka YouTube channel link bhejo",
            emptyList(),
        )
        assertEquals(WorkspaceExactLinkIntent.Platform.YOUTUBE, request?.platform)
        assertEquals("CarryMinati", request?.query)
    }

    @Test fun shortFollowUpCompletesPendingYouTubeLinkQuestion() {
        val prior = listOf(
            msg("u1", "user", "YouTube ka link bhejo", 1L),
            msg("a1", "assistant", "Kis channel ka link chahiye bro?", 2L),
        )
        val request = WorkspaceExactLinkIntent.decide("CarryMinati ka", prior)
        assertEquals(WorkspaceExactLinkIntent.Platform.YOUTUBE, request?.platform)
        assertEquals("CarryMinati", request?.query)
    }

    @Test fun videoAndPlaylistRequestsFallThroughToGenericWebDiscovery() {
        val direct = "YouTube pe CarryMinati latest video link do"
        assertNull(WorkspaceExactLinkIntent.decide(direct, emptyList()))
        val web = WorkspaceWebLinkIntent.decide(direct)
        assertEquals("youtube.com", web?.preferredHost)
        assertEquals(true, web?.query?.contains("CarryMinati", ignoreCase = true))

        assertNull(
            WorkspaceExactLinkIntent.decide(
                "playlist ka",
                listOf(
                    msg("u1", "user", "YouTube ka link bhejo", 1L),
                    msg("a1", "assistant", "Kis channel ka link chahiye bro?", 2L),
                ),
            )
        )
    }

    @Test fun genericYouTubeHomepageAskCanStillClarifyAndWritesNeverBecomeLookup() {
        assertNull(WorkspaceExactLinkIntent.decide("YouTube ka link bhejo", emptyList()))
        assertNull(WorkspaceExactLinkIntent.decide(
            "YouTube link feature add karo",
            emptyList(),
        ))
        assertNull(WorkspaceExactLinkIntent.decide(
            "thanks bro",
            listOf(
                msg("u1", "user", "YouTube ka link bhejo", 1L),
                msg("a1", "assistant", "Kis channel ka?", 2L),
            ),
        ))
    }
}
