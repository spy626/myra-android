package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceResearchSynthesisTest {
    private val work = WorkspacePublicWebSearch.Verified(
        "WorkManager official", "https://developer.android.com/topic/libraries/architecture/workmanager",
        "", "text/html",
    )
    private val service = WorkspacePublicWebSearch.Verified(
        "Foreground services", "https://developer.android.com/develop/background-work/services/fgs",
        "", "text/html",
    )

    private fun evidence(): WorkspaceResearchSynthesis.Bundle {
        val hits = listOf(
            WorkspacePublicResearch.Extract(work,
                listOf("WorkManager is designed for persistent work that can be deferred " +
                    "until its constraints are met and continues after the app exits.")),
            WorkspacePublicResearch.Extract(service,
                listOf("A foreground service performs user-noticeable ongoing work " +
                    "and must display a notification while it is running.")),
        )
        return requireNotNull(WorkspaceResearchSynthesis.bundle(
            "Compare WorkManager and Foreground Service", hits))
    }

    @Test fun differentPlatformDimensionsArePartialEvenWithBothSidesAccepted() {
        val question = "Compare Android and iOS battery optimization"
        val android = WorkspacePublicWebSearch.Verified(
            "About preserving battery - Android Developers",
            "https://developer.android.com/topic/performance/power",
            "Android battery optimization updates", "text/html")
        val ios = WorkspacePublicWebSearch.Verified(
            "iOS low power mode and battery optimization",
            "https://lifetips.alibaba.com/article/ios-low-power",
            "iOS battery optimization Low Power Mode reduces CPU frequency and mail fetch",
            "text/html")
        val bundle = WorkspaceResearchSynthesis.Bundle(question, listOf(
            WorkspaceResearchSynthesis.Evidence(
                "E1", "S1",
                "Android battery optimization OS updates preserve user experience and system health.",
                android),
            WorkspaceResearchSynthesis.Evidence(
                "E2", "S2",
                "iOS Low Power Mode reduces CPU frequency, disables mail fetch and pauses visual effects.",
                ios),
        ))
        val raw = """{"claims":[
          {"text":"Android battery optimization OS updates preserve user experience and system health.","evidenceIds":["E1"]},
          {"text":"iOS Low Power Mode reduces CPU frequency, disables mail fetch and pauses visual effects.","evidenceIds":["E2"]}
        ]}"""
        val result = WorkspaceResearchSynthesis.audit(raw, bundle)
        assertEquals(2, result.acceptedClaims)
        assertEquals(0, result.rejectedClaims)
        assertEquals(WorkspaceResearchSynthesis.AuditIssue.MISSING_COMPARISON_COVERAGE,
            result.issue)
        assertNull(result.answer)
        assertTrue(result.partialAnswer.orEmpty().contains(android.finalUrl))
        assertTrue(result.partialAnswer.orEmpty().contains(ios.finalUrl))
        assertFalse(result.isComplete)
    }

    @Test fun acceptedResearchShowsNativeComparisonAndOnlyCitedSourceLinks() {
        val bundle = evidence()
        val raw = """{"claims":[
          {"text":"WorkManager is designed for persistent work that can be deferred until constraints are met.","evidenceIds":["E1"]},
          {"text":"A foreground service performs user-noticeable ongoing work and must display a notification.","evidenceIds":["E2"]}
        ]}"""
        val answer = requireNotNull(WorkspaceResearchSynthesis.auditedAnswer(raw, bundle))
        assertTrue(answer.contains("### At a glance"))
        assertTrue(answer.contains("| Platform | Supported observation |"))
        assertTrue(answer.contains("### Sources used"))
        assertTrue(answer.contains(bundle.evidence[0].source.finalUrl))
        assertTrue(answer.contains(bundle.evidence[1].source.finalUrl))
    }

    @Test fun modelCanOnlyCiteVerifiedEvidenceIdentifiers() {
        val bundle = evidence()
        assertEquals(2, bundle.sourceCount)
        assertEquals(listOf("E1", "E2"), bundle.evidence.map { it.id })
        val valid = """{"claims":[
          {"text":"WorkManager is designed for persistent work that can be deferred until constraints are met.",
           "evidenceIds":["E1"]},
          {"text":"A foreground service performs user-noticeable ongoing work and must display a notification.",
           "evidenceIds":["E2"]}
        ]}"""
        val answer = requireNotNull(WorkspaceResearchSynthesis.auditedAnswer(valid, bundle))
        assertTrue(answer.contains(work.finalUrl))
        assertTrue(answer.contains(service.finalUrl))
        assertTrue(answer.contains("developer.android.com"))
    }

    @Test fun refusesInventedCitationsNumbersAndProviderUrls() {
        val bundle = evidence()
        assertNull(WorkspaceResearchSynthesis.auditedAnswer(
            """{"claims":[{"text":"WorkManager takes 999 minutes to execute persistent work.","evidenceIds":["E1"]}]}""",
            bundle))
        assertNull(WorkspaceResearchSynthesis.auditedAnswer(
            """{"claims":[{"text":"WorkManager is designed for persistent work that can be deferred.","evidenceIds":["E7"]}]}""",
            bundle))
        assertNull(WorkspaceResearchSynthesis.auditedAnswer(
            """{"claims":[{"text":"https://evil.example WorkManager is designed for persistent work.","evidenceIds":["E1"]}]}""",
            bundle))
    }

    @Test fun modelCannotFakeTwoSidedComparisonFromOnlyOneSide() {
        val bundle = evidence()
        assertNull(WorkspaceResearchSynthesis.auditedAnswer(
            """{"claims":[{"text":"WorkManager is designed for persistent work that can be deferred until constraints are met.","evidenceIds":["E1"]}]}""",
            bundle))
    }

    @Test fun noEvidenceDoesNotBecomeResearchAnswer() {
        assertNull(WorkspaceResearchSynthesis.bundle("Compare WorkManager", emptyList()))
        val text = WorkspaceResearchSynthesis.prompt(evidence())
        assertTrue(text.contains("E1"))
        assertTrue(text.contains("WorkManager versus Foreground Service"))
        assertTrue(WorkspaceResearchSynthesis.SYSTEM_INSTRUCTIONS.contains(
            "claim explicitly naming EACH requested subject"))

        assertFalse(text.contains("api_key"))
    }
}
