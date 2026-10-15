package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceYouTubeChannelSearchTest {
    @Test fun exactNamedChannelUsesStableChannelIdDestination() {
        val html = """
            <html><script>
            {"channelRenderer":{"channelId":"UC1111111111111111111111",
            "title":{"simpleText":"CarryMinati Fan"},"navigationEndpoint":{}}}
            {"channelRenderer":{"channelId":"UC2222222222222222222222",
            "title":{"simpleText":"CarryMinati"},"ownerBadges":[{"metadataBadgeRenderer":
            {"style":"BADGE_STYLE_TYPE_VERIFIED","label":"Verified"}}]}}
            </script></html>
        """.trimIndent()

        val found = WorkspaceYouTubeChannelSearch.findCandidate(html, "CarryMinati")
        assertEquals("CarryMinati", found?.title)
        assertEquals("UC2222222222222222222222", found?.channelId)
        assertTrue(found?.verifiedBadge == true)
        assertEquals(
            "https://www.youtube.com/channel/UC2222222222222222222222",
            found?.url,
        )
    }

    @Test fun unrelatedChannelIsNeverAcceptedAsExactDestination() {
        val html = """
            {"channelRenderer":{"channelId":"UC3333333333333333333333",
            "title":{"simpleText":"Different Creator"}}}
        """.trimIndent()
        assertNull(WorkspaceYouTubeChannelSearch.findCandidate(html, "CarryMinati"))
    }

    @Test fun requestIsPublicReadOnlyYouTubeSearch() {
        val request = WorkspaceYouTubeChannelSearch.request("CarryMinati")
        assertEquals("GET", request.method)
        assertEquals("www.youtube.com", request.url.host)
        assertTrue(request.url.encodedQuery.orEmpty().contains("search_query=CarryMinati"))
        assertNull(request.header("Authorization"))
        assertNull(request.header("Cookie"))
    }
}
