package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceAppPublisherDiscoveryTest {
    private val google = "https://play.google.com/store/apps/details?id=org.telegram.messenger"
    private val apple = "https://apps.apple.com/us/app/telegram-messenger/id686449807"
    private val html = """
        <html><head><title>Telegram Applications</title></head><body>
        <h1>Telegram</h1>
        <a href="https://play.google.com/store/apps/details?id=org.telegram.messenger">
            Telegram for Android (Google Play)</a>
        <a href="https://apps.apple.com/us/app/telegram-messenger/id686449807">
            Telegram for iPhone</a>
        <a href="/android">Telegram for Android (APK)</a>
        <a href="/dl/android/apk">APK download binary</a>
        </body></html>
    """.trimIndent()

    @Test fun independentSearchIsPublicAndBounded() {
        val request = WorkspaceAppPublisherDiscovery.searchRequest("Telegram")
        assertEquals("html.duckduckgo.com", request.url.host)
        assertEquals("GET", request.method)
        assertNull(request.header("Authorization"))
        assertNull(request.header("Cookie"))
    }

    @Test fun candidateMustHaveMatchingPublisherLikeHostNotImpersonator() {
        assertTrue(WorkspaceAppPublisherDiscovery.brandHost(
            "https://www.telegram.org/apps", "Telegram"))
        assertFalse(WorkspaceAppPublisherDiscovery.brandHost(
            "https://telegram-downloads.example.com/apps", "Telegram"))
        assertFalse(WorkspaceAppPublisherDiscovery.brandHost(
            "https://telegram.org.evil.com/apps", "Telegram"))
        assertFalse(WorkspaceAppPublisherDiscovery.brandHost(
            "https://play.google.com/store/apps/details?id=org.telegram.messenger", "Telegram"))
    }

    @Test fun siteNeedsExactStoreIdAndMatchingTitle() {
        val matched = WorkspaceAppPublisherDiscovery.inspect(
            "https://telegram.org/apps", html, "Telegram", listOf(google, apple)
        )
        assertEquals("https://telegram.org/apps", matched?.siteUrl)
        assertTrue(matched?.androidPages.orEmpty().contains("https://telegram.org/android"))
        assertNull(WorkspaceAppPublisherDiscovery.inspect(
            "https://telegram.org/apps",
            html.replace("org.telegram.messenger", "org.fake.messenger")
                .replace("id686449807", "id111111111"),
            "Telegram", listOf(google, apple)))
        assertNull(WorkspaceAppPublisherDiscovery.inspect(
            "https://telegram.org/apps",
            html.replace("Telegram Applications", "Wallpaper Downloads"),
            "Telegram", listOf(google)))
        assertNull(WorkspaceAppPublisherDiscovery.inspect(
            "https://telegram-downloads.example.com/apps", html,
            "Telegram", listOf(google)))
    }

    @Test fun androidPageIsSamePublisherWebsiteAndNotAnApkBinary() {
        assertTrue(WorkspaceAppPublisherDiscovery.verifiedAndroidPage(
            "https://telegram.org/apps", "https://telegram.org/android",
            "<html><title>Telegram for Android</title><body>" +
                "Download Telegram for Android on our website today.</body></html>",
            "Telegram"))
        assertFalse(WorkspaceAppPublisherDiscovery.verifiedAndroidPage(
            "https://telegram.org/apps", "https://wrong.example.com/android",
            "<title>Telegram for Android</title>" + "x".repeat(101), "Telegram"))
        assertFalse(WorkspaceAppPublisherDiscovery.verifiedAndroidPage(
            "https://telegram.org/apps", "https://telegram.org/android.apk",
            "<title>Telegram for Android</title>" + "x".repeat(101), "Telegram"))
    }

    @Test fun redirectsCannotSwitchToDifferentWebsite() {
        assertTrue(WorkspaceAppPublisherDiscovery.acceptsRedirect(
            "https://www.telegram.org/apps", "https://telegram.org/apps"))
        assertFalse(WorkspaceAppPublisherDiscovery.acceptsRedirect(
            "https://telegram.org/apps", "https://telegram.org.evil.com/apps"))
    }
}
