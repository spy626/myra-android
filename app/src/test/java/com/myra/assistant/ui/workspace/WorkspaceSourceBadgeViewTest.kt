package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSourceBadgeViewTest {
    private fun source(url: String) = WorkspaceVerifiedSourceStore.Source(
        title = "Verified", url = url, snippet = "",
        observedAtMs = 1000L, verifiedLabel = "reachable"
    )

    @Test fun platformSitesHaveRecognizableNativeIcons() {
        assertEquals(WorkspaceSourceBadgeView.Kind.GOOGLE_PLAY,
            WorkspaceSourceBadgeView.describe("play.google.com").kind)
        assertEquals(WorkspaceSourceBadgeView.Kind.APP_STORE,
            WorkspaceSourceBadgeView.describe("apps.apple.com").kind)
        assertEquals(WorkspaceSourceBadgeView.Kind.DOMAIN,
            WorkspaceSourceBadgeView.describe("cantina.com").kind)
        assertEquals("C", WorkspaceSourceBadgeView.describe("www.cantina.com").initial)
    }

    @Test fun previewShowsOnlyVerifiedDistinctWebsiteHostsUpToThree() {
        val preview = WorkspaceSourceBadgeView.preview(listOf(
            source("https://play.google.com/store/apps/details?id=com.signal.android"),
            source("https://play.google.com/store/apps/details?id=com.other"),
            source("https://apps.apple.com/us/app/cantina/id1234567890"),
            source("https://cantina.com/download"),
            source("https://github.com/moeru-ai/airi")
        ))
        assertEquals(listOf(
            "play.google.com", "apps.apple.com", "cantina.com"
        ), preview.map { it.host })
        assertEquals(3, preview.size)
        assertTrue(preview.none { it.host == "github.com" })
    }

    @Test fun genericResultsShowDomainInitialWithoutGuessingBrandIcons() {
        val fallback = WorkspaceSourceBadgeView.describe("docs.kotlinlang.org")
        assertEquals(WorkspaceSourceBadgeView.Kind.DOMAIN, fallback.kind)
        assertEquals("K", fallback.initial)
    }
}
