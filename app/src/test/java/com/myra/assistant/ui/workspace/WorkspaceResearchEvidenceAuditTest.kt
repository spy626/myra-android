package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REA-style evidence provenance ideas, adapted for LYRA's EXISTING single
 * research writer. A visible source URL is not itself proof of a model claim.
 */
class WorkspaceResearchEvidenceAuditTest {
    private val question =
        "LYRA, Android aur iOS ki battery optimization compare karo. " +
            "Multiple reliable websites se information lekar sources ke saath answer do."

    private val android = WorkspacePublicWebSearch.Verified(
        "Android battery optimization official guide",
        "https://developer.android.com/topic/performance/power",
        "Android battery optimization", "text/html")
    private val ios = WorkspacePublicWebSearch.Verified(
        "iOS battery optimization on iPhone",
        "https://support.apple.com/en-us/101604",
        "iOS battery optimization", "text/html")

    private val androidText =
        "Android battery optimization reduces background app activity to preserve " +
            "battery life and avoid unnecessary energy consumption."
    private val iosText =
        "iOS battery optimization conserves power by limiting background application " +
            "refresh on iPhone devices."

    private fun bundle() = requireNotNull(WorkspaceResearchSynthesis.bundle(question,
        listOf(
            WorkspacePublicResearch.Extract(android, listOf(androidText)),
            WorkspacePublicResearch.Extract(ios, listOf(iosText)),
        )))

    private val grounded =
        """{"claims":[
          {"text":"Android battery optimization reduces background app activity to preserve battery life.",
           "evidenceIds":["E1"]},
          {"text":"iOS battery optimization conserves power by limiting background application refresh.",
           "evidenceIds":["E2"]}
        ]}"""

    @Test fun ordinaryExistingJsonStillWorksAndReturnsEvidenceSummary() {
        val audited = WorkspaceResearchSynthesis.audit(grounded, bundle())
        assertTrue(audited.isComplete)
        assertEquals(WorkspaceResearchSynthesis.AuditIssue.NONE, audited.issue)
        assertEquals(2, audited.acceptedClaims)
        assertEquals(0, audited.rejectedClaims)
        val answer = requireNotNull(audited.answer)
        assertTrue(answer.contains(android.finalUrl))
        assertTrue(answer.contains(ios.finalUrl))
    }

    @Test fun oneOptionalMarkdownJsonFenceCanBeUnwrappedWithoutAcceptingProse() {
        val fenced = 96.toChar().toString().repeat(3)
        val response = fenced + "json\n" + grounded + "\n" + fenced
        assertTrue(WorkspaceResearchSynthesis.audit(response, bundle()).isComplete)
        assertEquals(WorkspaceResearchSynthesis.AuditIssue.MALFORMED_RESPONSE,
            WorkspaceResearchSynthesis.audit(
                "This is my opinion:\n" + grounded, bundle()).issue)
        assertEquals(WorkspaceResearchSynthesis.AuditIssue.MALFORMED_RESPONSE,
            WorkspaceResearchSynthesis.audit(
                fenced + "shell\n" + grounded + "\n" + fenced, bundle()).issue)
    }

    @Test fun sourceAnchoredNaturalParaphrasesUseActualQuotedEvidenceNotKeywordsAlone() {
        val paraphrased = """{"claims":[
          {"text":"Android battery optimization manages idle tasks and helps conserve device power.",
           "evidenceIds":["E1"],"supportQuote":""" + "\"" + androidText + "\"" + """},
          {"text":"iOS battery optimization cuts energy demand through control of app refresh.",
           "evidenceIds":["E2"],"supportQuote":""" + "\"" + iosText + "\"" + """}
        ]}"""
        val outcome = WorkspaceResearchSynthesis.audit(paraphrased, bundle())
        assertEquals(WorkspaceResearchSynthesis.AuditIssue.NONE, outcome.issue)
        assertNotNull(outcome.answer)
        assertTrue(outcome.answer.orEmpty().contains(ios.finalUrl))
    }

    @Test fun fabricatedQuoteWrongCitationAndMetricsRemainUnsupported() {
        val invented = """{"claims":[
          {"text":"Android battery optimization saves 3 extra hours on every phone.",
           "evidenceIds":["E1"],"supportQuote":""" + "\"" + androidText + "\"" + """},
          {"text":"iOS battery optimization conserves power by limiting background application refresh.",
           "evidenceIds":["E2"]}
        ]}"""
        val results = WorkspaceResearchSynthesis.audit(invented, bundle())
        assertEquals(WorkspaceResearchSynthesis.AuditIssue.MISSING_COMPARISON_COVERAGE,
            results.issue)
        assertNull(results.answer)
        assertFalse(results.isComplete)
        assertEquals(1, results.acceptedClaims)
        assertEquals(1, results.rejectedClaims)
        assertTrue(results.partialAnswer.orEmpty().contains("comparison incomplete"))
        assertTrue(results.partialAnswer.orEmpty().contains(ios.finalUrl))
        assertFalse(results.partialAnswer.orEmpty().contains("3 extra hours"))

        val fake = """{"claims":[
          {"text":"Android battery optimization manages idle tasks and helps conserve device power.",
           "evidenceIds":["E1"],"supportQuote":"This fabricated long quote does not exist in the source passage"},
          {"text":"iOS battery optimization cuts energy demand through control of app refresh.",
           "evidenceIds":["E2"],"supportQuote":"This is another fabricated quote absent from the actual source"}
        ]}"""
        assertEquals(WorkspaceResearchSynthesis.AuditIssue.NO_SUPPORTED_CLAIMS,
            WorkspaceResearchSynthesis.audit(fake, bundle()).issue)
    }

    @Test fun unknownEvidenceIdsAndUnsafeModelLinksCannotBecomeCitations() {
        val bad = """{"claims":[
          {"text":"Android battery optimization reduces background app activity to preserve battery life.",
           "evidenceIds":["E9"]},
          {"text":"https://evil.example/ iOS battery optimization conserves power by limiting background refresh.",
           "evidenceIds":["E2"]}
        ]}"""
        val result = WorkspaceResearchSynthesis.audit(bad, bundle())
        assertEquals(WorkspaceResearchSynthesis.AuditIssue.NO_SUPPORTED_CLAIMS, result.issue)
        assertEquals(2, result.rejectedClaims)
        assertNull(result.answer)
    }

    @Test fun researchEvidencePromptHasExplicitProvenanceAndNoExtraClient() {
        val prompt = WorkspaceResearchSynthesis.prompt(bundle())
        assertTrue(prompt.contains("E1 (S1)"))
        assertTrue(prompt.contains("E2 (S2)"))
        assertTrue(WorkspaceResearchSynthesis.SYSTEM_INSTRUCTIONS.contains("supportQuote"))
        assertFalse(prompt.contains("password=secret"))
    }
}
