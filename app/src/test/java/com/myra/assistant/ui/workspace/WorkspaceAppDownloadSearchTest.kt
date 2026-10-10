package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceAppDownloadSearchTest {
    private val apple = """
       {"resultCount":3,"results":[
         {"kind":"software","trackName":"Cantina: AI Characters",
          "trackViewUrl":"https://apps.apple.com/us/app/cantina/id1234567890",
          "sellerName":"Cantina, Inc","bundleId":"com.example.cantina",
          "sellerUrl":"https://www.cantina.com/download"},
         {"kind":"software","trackName":"Cantina Background Wallpapers",
          "trackViewUrl":"https://apps.apple.com/us/app/background/id1234567891",
          "sellerName":"Unknown","bundleId":"com.unknown.wallpaper"},
         {"kind":"software","trackName":"Fake Cantina",
          "trackViewUrl":"https://evil.example.com/app/id1234567892",
          "sellerName":"Unknown","bundleId":"com.unknown.fake"}
       ]}
    """.trimIndent()

    @Test fun appleSearchUsesPublicApiAndRejectsMismatchedResults() {
        val req = WorkspaceAppDownloadSearch.appleRequest("Cantina")
        assertEquals("itunes.apple.com", req.url.host)
        assertEquals("GET", req.method)
        assertNull(req.header("Authorization"))
        assertNull(req.header("Cookie"))
        val listing = requireNotNull(WorkspaceAppDownloadSearch.parseApple(apple, "Cantina"))
        assertEquals("Cantina, Inc", listing.seller)
        assertEquals("https://apps.apple.com/us/app/cantina/id1234567890", listing.storeUrl)
        assertEquals("https://www.cantina.com/download", listing.sellerUrl)
        assertFalse(WorkspaceAppDownloadSearch.appleUrl("https://evil.example.com/app/id1234567890"))
    }

    @Test fun storeLinksRequireRealTypedPathsAndIdentifiers() {
        assertTrue(WorkspaceAppDownloadSearch.playUrl(
            "https://play.google.com/store/apps/details?id=com.signal.android"))
        assertFalse(WorkspaceAppDownloadSearch.playUrl(
            "https://play.google.com/store/apps/details"))
        assertFalse(WorkspaceAppDownloadSearch.playUrl(
            "https://play.google.com/store/apps/developer?id=com.fake"))
        assertFalse(WorkspaceAppDownloadSearch.appleUrl(
            "https://apps.apple.com/us/developer/cantina/id1234567890"))
    }

    @Test fun parseGoogleOnlyAcceptsMatchingAppAndStorePages() {
        val html = """
          <a class="result__a" href="https://play.google.com/store/apps/details?id=com.bad.fake">
             Fake Cantina wallpapers - Apps on Google Play
          </a>
          <a class="result__a" href="https://play.google.com/store/apps/details?id=com.signal.android">
             Cantina: AI Characters - Apps on Google Play
          </a>
          <a class="result__a" href="https://github.com/someone/cantina">
             Cantina project
          </a>
        """.trimIndent()
        val results = WorkspaceAppDownloadSearch.parseGoogle(html, "Cantina")
        assertEquals(listOf("https://play.google.com/store/apps/details?id=com.signal.android"),
            results.map { it.url })
    }

    @Test fun missingPlatformsRemainExplicitRatherThanInvented() {
        val iphone = WorkspaceAppDownloadSearch.Result(
            WorkspaceAppDownloadSearch.Kind.IPHONE, "Cantina",
            "https://apps.apple.com/us/app/cantina/id1234567890",
            "Apple catalog listing · Seller: Cantina, Inc",
            "Apple catalog listing · reachable",
        )
        val text = WorkspaceAppDownloadSearch.reply("Cantina", listOf(iphone))
        assertTrue(text.contains("Not confirmed: Android, publisher website"))
        val sources = WorkspaceAppDownloadSearch.sources(listOf(iphone))
        assertEquals(1, sources.size)
        assertEquals(iphone.url, sources.single().url)
        assertFalse(text.contains("com.signal.android"))
    }

    @Test fun publisherWebsiteMustBeAppleMetadataAndPublicHttps() {
        assertNull(WorkspaceAppDownloadSearch.safePublisherWebsite("http://localhost/"))
        assertNull(WorkspaceAppDownloadSearch.safePublisherWebsite(
            "https://apps.apple.com/us/app/cantina/id1234567890"))
        assertEquals("https://www.cantina.com/download",
            WorkspaceAppDownloadSearch.safePublisherWebsite(
                "https://www.cantina.com/download"))
    }
}
