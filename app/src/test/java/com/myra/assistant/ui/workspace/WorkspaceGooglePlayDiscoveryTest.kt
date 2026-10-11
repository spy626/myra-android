package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGooglePlayDiscoveryTest {
    @Test fun nativeSearchIsReadOnlyFreeAndNotHardcodedToOneApp() {
        val telegram = WorkspaceGooglePlayDiscovery.searchRequest("Telegram")
        val cantina = WorkspaceGooglePlayDiscovery.searchRequest("Cantina")
        assertEquals("play.google.com", telegram.url.host)
        assertEquals("/store/search", telegram.url.encodedPath)
        assertTrue(telegram.url.queryParameter("q") == "Telegram")
        assertTrue(cantina.url.queryParameter("q") == "Cantina")
        assertEquals("GET", telegram.method)
        assertEquals(null, telegram.header("Authorization"))
        assertEquals(null, telegram.header("Cookie"))
    }

    @Test fun nativeSearchKeepsRealStorePackageAndDiscardsImpersonators() {
        val html = """
            <a href="/store/apps/details?id=org.telegram.messenger">
                <div><span>Telegram</span><span>Telegram FZ-LLC</span></div>
            </a>
            <a href="/store/apps/details?id=org.telegram.messenger">
                <span>Telegram</span>
            </a>
            <a href="/store/apps/details?id=org.telegram.messenger.web">
                <span>Telegram X</span>
            </a>
            <a href="https://play.google.com/store/apps/details?id=com.wallpaper.fake">
                <span>Telegram Wallpapers</span>
            </a>
            <a href="https://evil.example.com/store/apps/details?id=org.telegram.messenger">
                <span>Telegram</span>
            </a>
        """.trimIndent()
        val candidates = WorkspaceGooglePlayDiscovery.parseHtml(html, "Telegram")
        // Exact named card ranks first; other store search links are candidates
        // until the real app-page title is independently checked.
        assertEquals("https://play.google.com/store/apps/details?id=org.telegram.messenger",
            candidates.first().url)
        assertTrue(candidates.all { WorkspaceAppDownloadSearch.playUrl(it.url) })
        assertTrue(candidates.none { it.url.contains("evil.example.com") })
        // Unmatched first-party links remain candidates, never verified Sources.
        assertTrue(WorkspaceGooglePlayDiscovery.parseHtml(html, "Signal").all {
            WorkspaceAppDownloadSearch.playUrl(it.url)
        })
    }

    @Test fun embeddedPlaySearchDataFindsPackagesWithoutAnchorCards() {
        val embedded = """
            <html><script>AF_initDataCallback({data:
                [["/store/apps/details?id=org.telegram.messenger", "Telegram"],
                 ["/store/apps/details?id=org.telegram.messenger.web", "Telegram X"]]
            });</script></html>
        """.trimIndent()
        val items = WorkspaceGooglePlayDiscovery.parseHtml(embedded, "Telegram")
        assertEquals(2, items.size)
        assertEquals("https://play.google.com/store/apps/details?id=org.telegram.messenger",
            items[0].url)
        assertEquals("Google Play candidate", items[0].title)
    }

    @Test fun actualGooglePlayTitleIsRequiredForVerifiedListing() {
        val target = WorkspacePublicWebSearch.Verified(
            "Telegram - Apps on Google Play",
            "https://play.google.com/store/apps/details?id=org.telegram.messenger",
            "", "text/html"
        )
        assertTrue(WorkspaceGooglePlayDiscovery.pageTitleMatches("Telegram", target))
        assertFalse(WorkspaceGooglePlayDiscovery.pageTitleMatches(
            "Telegram", target.copy(title = "Telegram X - Apps on Google Play")))
        assertFalse(WorkspaceGooglePlayDiscovery.pageTitleMatches(
            "Telegram", target.copy(title = "Public app listing")))
        assertFalse(WorkspaceGooglePlayDiscovery.pageTitleMatches(
            "Telegram", target.copy(finalUrl = "https://example.com/store/apps/details?id=org.telegram.messenger")))
    }

    @Test fun disallowGuessedAndUntrustedDownloadLinks() {
        assertTrue(WorkspaceGooglePlayDiscovery.parseHtml(
            "<a href='https://evil.example.com/telegram.apk'>Telegram</a>",
            "Telegram"
        ).isEmpty())
        val unknown = WorkspaceGooglePlayDiscovery.parseHtml(
            "<a href='/store/apps/details?id=com.unrelated'>Different app</a>",
            "Telegram"
        )
        assertEquals(1, unknown.size)
        assertFalse(WorkspaceGooglePlayDiscovery.pageTitleMatches(
            "Telegram", WorkspacePublicWebSearch.Verified(
                "Different app - Apps on Google Play", unknown[0].url, "", "text/html"
            )
        ))
    }
}
