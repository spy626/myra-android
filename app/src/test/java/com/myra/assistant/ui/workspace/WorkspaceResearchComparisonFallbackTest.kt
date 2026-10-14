package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reproduces the phone finding: Apple evidence exists but the Free AI only wrote Android. */
class WorkspaceResearchComparisonFallbackTest {
    private val question =
        "LYRA, Android aur iOS ki battery optimization compare karo. " +
            "Multiple reliable websites se information lekar sources ke saath answer do."

    private val android = WorkspacePublicWebSearch.Verified(
        "Android battery optimization - Android Developers",
        "https://developer.android.com/develop/connectivity/preserving-battery",
        "How Android apps conserve energy and limit background activity.", "text/html")
    private val apple = WorkspacePublicWebSearch.Verified(
        "About optimized battery charging on iPhone - Apple Support",
        "https://support.apple.com/en-us/108055",
        "iOS battery life, charge limits and optimized charging.", "text/html")

    private val androidExcerpt =
        "Android battery optimization reduces unnecessary background work " +
            "by restricting certain tasks while device power is limited."
    private val iosExcerpt =
        "To improve the lifespan of your battery, your iPhone learns from " +
            "your daily charging habits and reduces time fully charged."

    @Test fun phoneFailureShowsBothLiteralSourceSidesWithoutPretendingAuditPassed() {
        val findings = WorkspaceResearchComparisonFallback.sideBySide(question, listOf(
            WorkspacePublicResearch.Extract(android, listOf(androidExcerpt)),
            WorkspacePublicResearch.Extract(apple, listOf(iosExcerpt)),
        ))
        assertNotNull(findings)
        val text = requireNotNull(findings)
        assertTrue(text.contains("**Android**"))
        assertTrue(text.contains("**iOS**"))
        assertTrue(text.contains(androidExcerpt))
        assertTrue(text.contains(iosExcerpt))
        assertTrue(text.contains(android.finalUrl))
        assertTrue(text.contains(apple.finalUrl))
        assertTrue(text.contains("not independently verified conclusions"))
    }

    @Test fun androidOnlyEvidenceDoesNotCreateImaginaryIosStatement() {
        val findings = WorkspaceResearchComparisonFallback.sideBySide(question, listOf(
            WorkspacePublicResearch.Extract(android, listOf(androidExcerpt)),
        ))
        assertNull(findings)
    }

    @Test fun iosInPageTitleIsNotEnoughIfPassageIsActuallyAboutAndroid() {
        val misleading = WorkspacePublicWebSearch.Verified(
            "iOS Android battery optimization comparison",
            "https://example.org/android-ios", "Comparison of battery savings", "text/html")
        val findings = WorkspaceResearchComparisonFallback.sideBySide(question, listOf(
            WorkspacePublicResearch.Extract(android, listOf(androidExcerpt)),
            WorkspacePublicResearch.Extract(misleading, listOf(
                "Android battery optimization reduces unnecessary background energy consumption.")),
        ))
        assertNull(findings)
    }

    @Test fun unsupportedMeasuredBatteryPromisesNeverReturnInFallback() {
        val findings = WorkspaceResearchComparisonFallback.sideBySide(question, listOf(
            WorkspacePublicResearch.Extract(android, listOf(
                "Android battery optimization can add 3 extra hours of battery life.")),
            WorkspacePublicResearch.Extract(apple, listOf(iosExcerpt)),
        ))
        assertNull(findings)
    }

    @Test fun onePublisherCannotClaimIndependentWebsiteComparison() {
        val other = WorkspacePublicWebSearch.Verified(
            "iOS and iPhone battery optimization",
            "https://support.google.com/android/battery",
            "iOS battery optimization on an Android help page", "text/html")
        assertNull(WorkspaceResearchComparisonFallback.sideBySide(question, listOf(
            WorkspacePublicResearch.Extract(android, listOf(androidExcerpt)),
            WorkspacePublicResearch.Extract(other, listOf(
                "iPhone battery optimization reduces unnecessary background power usage.")),
        )))
    }
}
