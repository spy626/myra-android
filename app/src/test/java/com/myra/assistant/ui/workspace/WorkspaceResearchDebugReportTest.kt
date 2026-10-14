package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bounded diagnostics only; not a second researcher, storage owner or model route. */
class WorkspaceResearchDebugReportTest {
    private val question = "Compare Android and iOS battery optimization"
    private val android = WorkspacePublicWebSearch.Verified(
        "Android battery optimization - Google",
        "https://developer.android.com/topic/performance/power",
        "Android battery optimization", "text/html")
    private val apple = WorkspacePublicWebSearch.Verified(
        "iOS battery optimization - Apple",
        "https://support.apple.com/en-us/101604",
        "iOS battery optimization", "text/html")
    private val androidPassage = "Android battery optimization reduces background app activity " +
        "to preserve battery life and avoid unnecessary energy consumption."
    private val iosPassage = "iOS battery optimization conserves power by limiting background " +
        "application refresh on iPhone devices."

    private fun bundle() = requireNotNull(WorkspaceResearchSynthesis.bundle(
        question, listOf(
            WorkspacePublicResearch.Extract(android, listOf(androidPassage)),
            WorkspacePublicResearch.Extract(apple, listOf(iosPassage)),
        )))

    @Test fun acceptedAndRejectedClaimsExplainIncompletePlatformCoverage() {
        val input = """{"claims":[
          {"text":"Android battery optimization reduces background app activity to preserve battery life.",
           "evidenceIds":["E1"]},
          {"text":"iOS battery optimization conserves power through background app refresh.",
           "evidenceIds":["E9"]}
        ]}"""
        val audit = WorkspaceResearchSynthesis.audit(input, bundle())
        assertEquals(WorkspaceResearchSynthesis.AuditIssue.MISSING_COMPARISON_COVERAGE,
            audit.issue)
        assertEquals(2, audit.claimDecisions.size)
        assertEquals(WorkspaceResearchSynthesis.ClaimRejection.ACCEPTED,
            audit.claimDecisions[0].result)
        assertEquals(WorkspaceResearchSynthesis.ClaimRejection.UNKNOWN_EVIDENCE_IDS,
            audit.claimDecisions[1].result)
        assertTrue(audit.claimDecisions[0].namesFirstSubject)
        assertTrue(audit.claimDecisions[1].namesSecondSubject)
        assertEquals(listOf("E9"), audit.claimDecisions[1].evidenceIds)
        val report = WorkspaceResearchDebugReport.summarize(audit)
        assertTrue(report.contains("MISSING_COMPARISON_COVERAGE"))
        assertTrue(report.contains("Claim 2: UNKNOWN_EVIDENCE_IDS"))
        assertTrue(report.contains("Accepted subject 1 claims: 1"))
        assertTrue(report.contains("Accepted subject 2 claims: 0"))
        assertFalse(report.contains("background app activity"))
        assertFalse(report.contains("support.apple.com"))
    }

    @Test fun identifiesUnsupportedQuoteAndLiteralWithoutRetainingModelText() {
        val fakeQuote = "Sensitive account password=never-save-this-from-a-model"
        val input = """{"claims":[
          {"text":"Android battery optimization reduces background app activity to preserve battery life.",
           "evidenceIds":["E1"],"supportQuote":""" + "\"" + fakeQuote + "\"" + """},
          {"text":"iOS battery optimization provides 999 extra hours of battery life.",
           "evidenceIds":["E2"]}
        ]}"""
        val audit = WorkspaceResearchSynthesis.audit(input, bundle())
        assertEquals(WorkspaceResearchSynthesis.ClaimRejection.UNSUPPORTED_QUOTE,
            audit.claimDecisions[0].result)
        // This number has no matching literal or independent measured source.
        assertTrue(audit.claimDecisions[1].result in setOf(
            WorkspaceResearchSynthesis.ClaimRejection.UNSUPPORTED_LITERAL,
            WorkspaceResearchSynthesis.ClaimRejection.INSUFFICIENT_PASSAGE_SUPPORT))
        val report = WorkspaceResearchDebugReport.summarize(audit)
        assertFalse(report.contains("password"))
        assertFalse(report.contains("never-save"))
        assertFalse(report.contains("999"))
        assertFalse(report.contains("Sensitive account"))
    }

    @Test fun malformedModelResponseShowsShapeNotRawBody() {
        val raw = "not-json-password=do-not-leak"
        val audit = WorkspaceResearchSynthesis.audit(raw, bundle())
        assertEquals(WorkspaceResearchSynthesis.AuditIssue.MALFORMED_RESPONSE, audit.issue)
        val report = WorkspaceResearchDebugReport.summarize(audit)
        assertTrue(report.contains("INVALID_JSON_OR_SCHEMA"))
        assertTrue(report.contains("Model response characters: " + raw.length))
        assertFalse(report.contains("do-not-leak"))
        assertFalse(report.contains(raw))
    }

    @Test fun inMemoryReportIsForLatestExactTurnAndCanBeCleared() {
        val store = WorkspaceResearchDebugReport()
        val raw = """{"claims":[
          {"text":"Android battery optimization reduces background app activity to preserve battery life.",
           "evidenceIds":["E1"]},
          {"text":"iOS battery optimization conserves power by limiting background application refresh.",
           "evidenceIds":["E2"]}
        ]}"""
        val audit = WorkspaceResearchSynthesis.audit(raw, bundle())
        assertTrue(audit.isComplete)
        store.record("turn-1", audit)
        assertNull(store.forTurn("turn-2"))
        assertTrue(store.forTurn("turn-1").orEmpty().contains("Final audit: NONE"))
        store.clear()
        assertNull(store.forTurn("turn-1"))
        store.record("turn-2", audit)
        assertNull(store.forTurn("turn-1"))
        assertTrue(store.forTurn("turn-2").orEmpty().contains("Claim 2: ACCEPTED"))
    }
}
