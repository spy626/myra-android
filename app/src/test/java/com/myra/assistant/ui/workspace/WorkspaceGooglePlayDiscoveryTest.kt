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
        assertEquals(listOf("https://play.google.com/store/apps/details?id=org.telegram.messenger"),
            candidates.map { it.url })
        assertTrue(WorkspaceGooglePlayDiscovery.parseHtml(html, "Signal").isEmpty())
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
        assertTrue(WorkspaceGooglePlayDiscovery.parseHtml(
            "<a href='/store/apps/details?id=com.unrelated'>Different app</a>",
            "Telegram"
        ).isEmpty())
    }
}
