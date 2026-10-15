package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone evidence: two third-party articles suggested battery-life-hour numbers. */
class WorkspaceResearchEvidenceQualityTest {
    private val question =
        "LYRA, Android aur iOS ki battery optimization compare karo. " +
            "Multiple reliable websites se information lekar sources ke saath answer do."
    private fun source(title: String, url: String, snippet: String = title) =
        WorkspacePublicWebSearch.Verified(title, url, snippet, "text/html")
    private val google = source(
        "Android battery optimization - Android Developers",
        "https://developer.android.com/topic/performance/power")
    private val apple = source(
        "iPhone iOS battery life and Low Power Mode - Apple Support",
        "https://support.apple.com/en-us/101604")
    private val blog = source(
        "Android iOS battery optimization: claims and comparisons",
        "https://lifetips.alibaba.com/article/battery-optimization")
    private val blog2 = source(
        "Android iOS battery optimization on modern phones",
        "https://digitalreachsolutions.com/iphone-vs-android-battery")

    @Test fun primaryPublishersRankAheadOfRelevantSecondaryArticles() {
        val chosen = WorkspaceResearchQueryPlan.choose(question,
            WorkspaceWebLinkIntent.Request("Android iOS battery optimization", maxResults = 3),
            listOf(blog, blog2, google, apple))
        assertEquals(3, chosen.size)
        assertTrue(chosen.contains(google))
        assertTrue(chosen.contains(apple))
        assertTrue(chosen.any { it == blog || it == blog2 })
        assertTrue(WorkspaceResearchEvidenceQuality.primaryFor(google, question))
        assertTrue(WorkspaceResearchEvidenceQuality.primaryFor(apple, question))
        assertFalse(WorkspaceResearchEvidenceQuality.primaryFor(blog, question))
    }

    @Test fun publisherGroupsSubdomainsAndGoogleAndroidOwnership() {
        assertEquals("google.com", WorkspaceResearchEvidenceQuality.publisher(
            "https://developer.android.com/topic/performance/power"))
        assertEquals("google.com", WorkspaceResearchEvidenceQuality.publisher(
            "https://support.google.com/android/answer/"))
        assertEquals("apple.com", WorkspaceResearchEvidenceQuality.publisher(
            "https://support.apple.com/en-us/101604"))
        assertEquals("apple.com", WorkspaceResearchEvidenceQuality.publisher(
            "https://developer.apple.com/documentation/"))
        assertEquals("example.co.uk", WorkspaceResearchEvidenceQuality.publisher(
            "https://news.example.co.uk/article"))
    }

    @Test fun recognizesComparableMeasuredBatteryClaimsIncludingExtraHours() {
        assertEquals(setOf("3:hour"),
            WorkspaceResearchEvidenceQuality.measurements("iOS saves up to 3 extra hours"))
        assertEquals(setOf("2+:hour"),
            WorkspaceResearchEvidenceQuality.measurements("Android can gain 2+ hours"))
        assertEquals(setOf("3:hour"),
            WorkspaceResearchEvidenceQuality.measurements("iPhone gained three additional hours"))
        assertEquals(setOf("20:%"),
            WorkspaceResearchEvidenceQuality.measurements("Battery use fell 20%"))
        assertTrue(WorkspaceResearchEvidenceQuality.measurements(
            "Android 15 background-task API updates").isEmpty())
    }

    @Test fun requestsMultipleWebsitesNeedTwoIndependentlyOwnedPublishers() {
        assertTrue(WorkspaceResearchEvidenceQuality.requestsMultiplePublishers(question))
        val sameOwner = listOf(
            WorkspacePublicResearch.Extract(google, listOf(
                "Android and iOS battery optimization uses background activity constraints.")),
            WorkspacePublicResearch.Extract(source(
                "iOS battery optimization article on Android",
                "https://support.google.com/android/battery"), listOf(
                "Android and iOS battery optimization reduces background tasks.")),
        )
        assertEquals(1, WorkspaceResearchEvidenceQuality.publisherCount(sameOwner))
        assertNull(WorkspaceResearchSynthesis.bundle(question, sameOwner))
    }

    @Test fun onePublisherNumericalPromiseIsNotCitedAsVerifiedFact() {
        val androidText =
            "Android battery optimization uses background app limits and can extend " +
                "battery life by up to 3 extra hours on some phones."
        val iosText =
            "iOS battery optimization limits background application activity to " +
                "save energy and reduce power consumption on iPhone."
        val bundle = requireNotNull(WorkspaceResearchSynthesis.bundle(question, listOf(
            WorkspacePublicResearch.Extract(blog, listOf(androidText)),
            WorkspacePublicResearch.Extract(apple, listOf(iosText)),
        )))
        val raw = """{"claims":[
          {"text":"Android battery optimization limits background apps and can extend battery life by 3 extra hours.","evidenceIds":["E1"]},
          {"text":"Android battery optimization uses background app limits to save energy.","evidenceIds":["E1"]},
          {"text":"iOS battery optimization limits background application activity to save energy.","evidenceIds":["E2"]}
        ]}"""
        val answer = requireNotNull(WorkspaceResearchSynthesis.auditedAnswer(raw, bundle))
        assertFalse(answer.contains("3 extra hours"))
        assertTrue(answer.contains("Android battery optimization uses background"))
        assertTrue(answer.contains("iOS battery optimization"))
        assertTrue(answer.contains(blog.finalUrl))
        assertTrue(answer.contains(apple.finalUrl))
    }

    @Test fun samePublisherOnTwoSubdomainsCannotCorroborateNumericClaim() {
        val e1 = WorkspaceResearchSynthesis.Evidence("E1", "S1",
            "Android battery optimization can extend battery life by 3 extra hours.",
            google)
        val e2 = WorkspaceResearchSynthesis.Evidence("E2", "S2",
            "Android battery optimization may add up to 3 hours of battery life.",
            source("Android battery tips", "https://support.google.com/android/battery"))
        assertFalse(WorkspaceResearchEvidenceQuality.corroboratesMeasurements(
            "Android battery optimization gives 3 extra hours", listOf(e1, e2), question))
    }

    @Test fun independentMatchingPublishersPermitMeasuredClaimWithoutInventingUrls() {
        val e1 = WorkspaceResearchSynthesis.Evidence("E1", "S1",
            "Android battery optimization can extend battery life by 3 extra hours.",
            google)
        val e2 = WorkspaceResearchSynthesis.Evidence("E2", "S2",
            "Android battery optimization can extend battery life by up to 3 hours.",
            blog)
        assertTrue(WorkspaceResearchEvidenceQuality.corroboratesMeasurements(
            "Android battery optimization can extend battery life by 3 hours",
            listOf(e1, e2), question))
        assertFalse(WorkspaceResearchEvidenceQuality.corroboratesMeasurements(
            "iOS battery optimization can extend battery life by 3 hours",
            listOf(e1, e2), question))
    }

    @Test fun extractiveFallbackOmitsSingleSourceHoursButKeepsUsefulQualitativeText() {
        val extracts = listOf(
            WorkspacePublicResearch.Extract(blog, listOf(
                "Android battery optimization can increase battery life by 3 extra hours.",
                "Android battery optimization limits background app activity to reduce energy use.")),
            WorkspacePublicResearch.Extract(apple, listOf(
                "iOS battery optimization and Low Power Mode reduce background energy usage.")),
        )
        val safe = WorkspaceResearchEvidenceQuality.safeFallbackExtracts(extracts)
        assertEquals(2, safe.size)
        assertEquals(1, safe.first().passages.size)
        assertFalse(safe.first().passages.first().contains("3 extra hours"))
    }

    @Test fun auditedOptionalTakeawayIsGroundedAndCitedToBothPlatforms() {
        val androidText =
            "Android battery optimization limits unnecessary background app tasks " +
                "to reduce energy use and preserve battery life."
        val iosText =
            "iOS battery optimization limits unnecessary background app tasks " +
                "to reduce energy use and preserve battery life."
        val bundle = requireNotNull(WorkspaceResearchSynthesis.bundle(question, listOf(
            WorkspacePublicResearch.Extract(google, listOf(androidText)),
            WorkspacePublicResearch.Extract(apple, listOf(iosText)),
        )))
        val raw = """{"claims":[
          {"text":"Android battery optimization limits unnecessary background app tasks to reduce energy use.","evidenceIds":["E1"]},
          {"text":"iOS battery optimization limits unnecessary background app tasks to reduce energy use.","evidenceIds":["E2"]}
        ],"takeaway":{
          "text":"Android and iOS battery optimization both limit unnecessary background app tasks to preserve battery life.",
          "evidenceIds":["E1","E2"]
        }}"""
        val answer = requireNotNull(WorkspaceResearchSynthesis.auditedAnswer(raw, bundle))
        assertTrue(answer.contains("Practical takeaway"))
        assertTrue(answer.contains(google.finalUrl))
        assertTrue(answer.contains(apple.finalUrl))
    }

    @Test fun mixedPlatformQueriesCanDiscoverAppleOfficialSupport() {
        assertNull(WorkspacePublicWebSearch.officialDocumentationHost(
            "Android iOS battery optimization"))
        assertEquals("support.apple.com",
            WorkspacePublicWebSearch.officialDocumentationHost("iOS battery optimization"))
    }
}
