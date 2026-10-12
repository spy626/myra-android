package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exact first-phone-test request: Android/iOS BATTERY, not GitHub LYRA or APK optimization. */
class WorkspaceQuestionAwareResearchPhoneRegressionTest {
    private val question =
        "LYRA, Android aur iOS ki battery optimization compare karo. " +
            "Multiple reliable websites se information lekar sources ke saath answer do."

    private val android = WorkspacePublicWebSearch.Verified(
        "Android battery optimization and battery life",
        "https://developer.android.com/topic/performance/power",
        "Android reduces battery use by controlling background app activity",
        "text/html",
    )
    private val ios = WorkspacePublicWebSearch.Verified(
        "iOS battery optimization on iPhone",
        "https://support.apple.com/iphone-battery",
        "Apple explains how iPhone and iOS manage battery and background usage",
        "text/html",
    )
    private val wrongGithub = WorkspacePublicWebSearch.Verified(
        "google/lyra voice codec — GitHub repository",
        "https://github.com/google/lyra",
        "The Lyra codec compresses audio for low bitrate voice communication",
        "text/html",
    )
    private val wrongAndroid = WorkspacePublicWebSearch.Verified(
        "Optimize Android apps with R8",
        "https://developer.android.com/build/shrink-code",
        "App APK size optimization and Jetpack Compose minification",
        "text/html",
    )
    private val wrongIos = WorkspacePublicWebSearch.Verified(
        "iOS application code performance",
        "https://developer.apple.com/documentation/xcode",
        "How to build iOS applications efficiently with Xcode",
        "text/html",
    )

    @Test fun phonePromptKeepsQuestionSubjectsAndBatteryTopicNotAssistantName() {
        val request = requireNotNull(WorkspaceWebResearchIntent.decide(question))
        assertEquals(question, request.question)
        val query = request.search.query.lowercase()
        assertTrue("Must preserve Android", "android" in query)
        assertTrue("Must preserve iOS", "ios" in query)
        assertTrue("Must preserve BATTERY", "battery" in query)
        assertTrue("Must preserve optimization", "optimization" in query)
        assertFalse("Do not search for assistant name", "lyra" in query)
        assertFalse("Do not search instruction filler", "websites" in query)
        assertEquals(listOf("Android", "iOS"),
            WorkspacePublicResearch.comparisonSubjects(question))
    }

    @Test fun targetedSubqueriesKeepBatteryContextForBothPlatforms() {
        val request = requireNotNull(WorkspaceWebResearchIntent.decide(question))
        val planned = WorkspaceResearchQueryPlan.queries(question, request.search)
        assertEquals(3, planned.size)
        assertTrue(planned[1].query.contains("Android battery optimization"))
        assertTrue(planned[2].query.contains("iOS battery optimization"))
        assertTrue(planned.all { it.preferredHost == null })
        assertNull(WorkspacePublicWebSearch.officialDocumentationHost(request.search.query))
    }

    @Test fun irrelevantLiveWebsitesNeverDisplaceActualQuestionSources() {
        val results = listOf(wrongGithub, wrongAndroid, wrongIos, android, ios)
        val picked = WorkspaceResearchQueryPlan.choose(
            question, requireNotNull(WorkspaceWebResearchIntent.decide(question)).search,
            results)
        assertTrue(picked.contains(android))
        assertTrue(picked.contains(ios))
        assertFalse(picked.contains(wrongGithub))
        assertFalse(picked.contains(wrongAndroid))
        assertFalse(picked.contains(wrongIos))
        assertTrue(WorkspaceResearchQuestionFocus.coversQuestion(question, picked))
    }

    @Test fun oneSidedResultsNeverBecomeCompleteComparison() {
        assertFalse(WorkspaceResearchQuestionFocus.coversQuestion(
            question, listOf(android, wrongGithub, wrongAndroid)))
        assertFalse(WorkspaceResearchQuestionFocus.coversQuestion(
            question, listOf(wrongGithub, wrongAndroid, wrongIos)))
    }

    private fun groundedBundle(): WorkspaceResearchSynthesis.Bundle {
        val androidText =
            "Android battery optimization reduces background energy usage and saves " +
                "battery life by limiting unnecessary app activity on Android phones."
        val iosText =
            "iOS battery optimization reduces background energy usage and saves " +
                "battery life by limiting unnecessary app activity on iPhone."
        return requireNotNull(WorkspaceResearchSynthesis.bundle(question, listOf(
            WorkspacePublicResearch.Extract(android, listOf(androidText)),
            WorkspacePublicResearch.Extract(ios, listOf(iosText)),
        )))
    }

    @Test fun answerMustBeOnBatteryTopicAndCoverBothSubjectsWithCitations() {
        val bundle = groundedBundle()
        val wrong = """{"claims":[
          {"text":"Android uses Jetpack Compose for building application interfaces.","evidenceIds":["E1"]},
          {"text":"iOS development uses Xcode for compiling application packages.","evidenceIds":["E2"]}
        ]}"""
        assertNull(WorkspaceResearchSynthesis.auditedAnswer(wrong, bundle))

        val correct = """{"claims":[
          {"text":"Android battery optimization reduces background energy usage and saves battery life.","evidenceIds":["E1"]},
          {"text":"iOS battery optimization reduces background energy usage and saves battery life.","evidenceIds":["E2"]}
        ]}"""
        val answer = requireNotNull(WorkspaceResearchSynthesis.auditedAnswer(correct, bundle))
        assertTrue(answer.contains(android.finalUrl))
        assertTrue(answer.contains(ios.finalUrl))
        assertFalse(answer.contains("Evidence-linked findings only"))
    }

    @Test fun batteryPageTitleCannotTurnUnrelatedAppCodeIntoBatteryEvidence() {
        val irrelevantParagraph =
            "Android application optimization uses R8 compilation and code minification " +
                "when building Jetpack Compose interfaces for production applications."
        val extracted = WorkspaceResearchQuestionFocus.relevantExtracts(question, listOf(
            WorkspacePublicResearch.Extract(android, listOf(irrelevantParagraph)),
            WorkspacePublicResearch.Extract(ios, listOf(
                "iOS battery optimization reduces background power use and improves battery life."
            )),
        ))
        assertEquals(1, extracted.size)
        assertEquals(ios.finalUrl, extracted.first().source.finalUrl)
        assertNull(WorkspaceResearchSynthesis.bundle(question, extracted))
    }

    @Test fun singlePlatformCannotSupplyFakeTwoSidedModelComparison() {
        assertNull(WorkspaceResearchSynthesis.bundle(question, listOf(
            WorkspacePublicResearch.Extract(android, listOf(
                "Android battery optimization reduces power usage on modern Android phones."
            ))
        )))
    }

    @Test fun naturalDifferentFromComparisonStillUnderstandsBothSubjects() {
        assertEquals(listOf("WorkManager", "Foreground Service"),
            WorkspacePublicResearch.comparisonSubjects(
                "How is WorkManager different from Foreground Service?"))
    }
}
