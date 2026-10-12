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
        assertTrue(answer.contains("Source 2"))
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
        assertFalse(text.contains("api_key"))
    }
}
