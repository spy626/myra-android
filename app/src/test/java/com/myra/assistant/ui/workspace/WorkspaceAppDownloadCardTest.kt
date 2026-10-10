package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceAppDownloadCardTest {
    private fun src(kind: String, url: String, info: String, status: String) =
        WorkspaceVerifiedSourceStore.Source(
            title = "Cantina — " + kind, url = url, snippet = info,
            observedAtMs = 1L, verifiedLabel = status
        )

    @Test fun onlyVerifiedAppDownloadReplyWithMatchingSourcesProducesCard() {
        val raw = "**Cantina — app download links**\n\n📱 **Android · Google Play**"
        assertNull(WorkspaceAppDownloadCard.parse(raw, emptyList()))
        val data = WorkspaceAppDownloadCard.parse(raw, listOf(src(
            "Google Play Store",
            "https://play.google.com/store/apps/details?id=com.signal.android",
            "Google Play listing", "Google Play listing · reachable",
        )))
        assertEquals("Cantina", data?.appName)
        assertEquals(listOf(WorkspaceAppDownloadCard.Platform.ANDROID),
            data?.destinations?.map { it.platform })
        assertNull(WorkspaceAppDownloadCard.parse(
            "**Cantina — app download links**",
            listOf(src("Google Play Store", "https://evil.example.com/app.apk",
                "Invalid", "reachable"))))
    }

    @Test fun sortedPlatformsPreserveOriginalVerifiedUrlsAndSellerEvidence() {
        val raw = "**Cantina — app download links**\n\nNot confirmed: publisher website."
        val data = requireNotNull(WorkspaceAppDownloadCard.parse(raw, listOf(
            src("Apple App Store", "https://apps.apple.com/us/app/cantina/id1234567890",
                "Apple catalog listing · Seller: Cantina, Inc",
                "Apple catalog listing · reachable"),
            src("Google Play Store",
                "https://play.google.com/store/apps/details?id=com.signal.android",
                "Google Play listing · matched app name",
                "Google Play listing · reachable"),
        )))
        assertEquals("Cantina, Inc", data.developer)
        assertEquals(listOf(
            WorkspaceAppDownloadCard.Platform.ANDROID,
            WorkspaceAppDownloadCard.Platform.IPHONE,
        ), data.destinations.map { it.platform })
        assertEquals("publisher website.", data.missing)
    }

    @Test fun publisherCardRequiresEvidenceLabelAndNeverInventsWebsite() {
        val raw = "**Cantina — app download links**"
        val publisher = src("Publisher website", "https://www.cantina.com/download",
            "Publisher URL", "Publisher-linked site · reachable")
        assertEquals(WorkspaceAppDownloadCard.Platform.WEBSITE,
            WorkspaceAppDownloadCard.parse(raw, listOf(publisher))
                ?.destinations?.single()?.platform)
        assertNull(WorkspaceAppDownloadCard.parse(raw, listOf(
            publisher.copy(verifiedLabel = "Live web result · reachable"))))
    }
}
