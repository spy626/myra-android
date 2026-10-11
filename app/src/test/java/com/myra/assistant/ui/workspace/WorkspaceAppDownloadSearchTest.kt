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

    @Test fun googlePlayListingTitleVariantsAreVerifiedWithoutAcceptingTelegramX() {
        assertTrue(WorkspaceAppDownloadSearch.storeNameMatches(
            "Telegram", "Telegram – Apps on Google Play"))
        assertTrue(WorkspaceAppDownloadSearch.storeNameMatches(
            "Telegram", "Telegram - Apps on Google Play"))
        assertFalse(WorkspaceAppDownloadSearch.storeNameMatches(
            "Telegram", "Telegram X – Apps on Google Play"))
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

    @Test fun redirectsCannotChangeAppIdOrPublisherWebsite() {
        assertTrue(WorkspaceAppDownloadSearch.sameStoreIdentity(
            "https://apps.apple.com/us/app/cantina/id1234567890",
            "https://apps.apple.com/in/app/cantina/id1234567890",
            WorkspaceAppDownloadSearch.Kind.IPHONE
        ))
        assertFalse(WorkspaceAppDownloadSearch.sameStoreIdentity(
            "https://apps.apple.com/us/app/cantina/id1234567890",
            "https://apps.apple.com/in/app/clone/id2222222222",
            WorkspaceAppDownloadSearch.Kind.IPHONE
        ))
        assertFalse(WorkspaceAppDownloadSearch.sameStoreIdentity(
            "https://play.google.com/store/apps/details?id=com.signal.android",
            "https://play.google.com/store/apps/details?id=com.clone.android",
            WorkspaceAppDownloadSearch.Kind.ANDROID
        ))
        assertFalse(WorkspaceAppDownloadSearch.sameStoreIdentity(
            "https://www.cantina.com/download",
            "https://wrong.example.com/download",
            WorkspaceAppDownloadSearch.Kind.PUBLISHER
        ))
        assertTrue(WorkspaceAppDownloadSearch.sameStoreIdentity(
            "https://www.cantina.com/download",
            "https://cantina.com/download",
            WorkspaceAppDownloadSearch.Kind.PUBLISHER
        ))
    }

    @Test fun optionalPublisherAndroidLandingDoesNotClaimDownloadedApk() {
        val site = WorkspaceAppDownloadSearch.Result(
            WorkspaceAppDownloadSearch.Kind.PUBLISHER, "Telegram",
            "https://telegram.org/apps", "App site cross-links a verified store",
            "Store-linked app website · reachable"
        )
        val androidPage = WorkspaceAppDownloadSearch.Result(
            WorkspaceAppDownloadSearch.Kind.PUBLISHER_ANDROID, "Telegram",
            "https://telegram.org/android", "Android landing page on publisher site",
            "Store-linked Android page · reachable"
        )
        val reply = WorkspaceAppDownloadSearch.reply("Telegram", listOf(site, androidPage))
        assertTrue(reply.contains("Open publisher Android download page"))
        assertFalse(reply.contains("APK installed"))
        assertEquals(2, WorkspaceAppDownloadSearch.sources(listOf(site, androidPage)).size)
        assertTrue(WorkspaceAppDownloadSearch.sources(listOf(site, androidPage))
            .last().title.endsWith(" — Publisher Android page"))
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
        assertTrue(text.contains("[Download for iPhone ↗](https://apps.apple.com/"))
        val sources = WorkspaceAppDownloadSearch.sources(listOf(iphone))
        assertEquals(1, sources.size)
        assertEquals(iphone.url, sources.single().url)
        assertFalse(text.contains("com.signal.android"))
    }

    @Test fun platformLinksAndSourcesUseAndroidThenIphoneThenPublisherOrder() {
        val iphone = WorkspaceAppDownloadSearch.Result(
            WorkspaceAppDownloadSearch.Kind.IPHONE, "Cantina",
            "https://apps.apple.com/us/app/cantina/id1234567890", "Seller: Cantina",
            "Apple catalog listing · reachable",
        )
        val android = WorkspaceAppDownloadSearch.Result(
            WorkspaceAppDownloadSearch.Kind.ANDROID, "Cantina",
            "https://play.google.com/store/apps/details?id=com.signal.android",
            "Google Play listing", "Google Play listing · reachable",
        )
        val publisher = WorkspaceAppDownloadSearch.Result(
            WorkspaceAppDownloadSearch.Kind.PUBLISHER, "Cantina",
            "https://www.cantina.com/download", "Publisher website",
            "Publisher-linked site · reachable",
        )
        val text = WorkspaceAppDownloadSearch.reply(
            "Cantina", listOf(iphone, publisher, android)
        )
        assertTrue(text.indexOf("Download for Android") < text.indexOf("Download for iPhone"))
        assertTrue(text.indexOf("Download for iPhone") < text.indexOf("Visit publisher website"))
        assertEquals(listOf(
            WorkspaceAppDownloadSearch.Kind.ANDROID,
            WorkspaceAppDownloadSearch.Kind.IPHONE,
            WorkspaceAppDownloadSearch.Kind.PUBLISHER,
        ), WorkspaceAppDownloadSearch.sources(listOf(iphone, publisher, android))
            .map { src ->
                when {
                    src.url.contains("play.google.com") -> WorkspaceAppDownloadSearch.Kind.ANDROID
                    src.url.contains("apps.apple.com") -> WorkspaceAppDownloadSearch.Kind.IPHONE
                    else -> WorkspaceAppDownloadSearch.Kind.PUBLISHER
                }
            })
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
