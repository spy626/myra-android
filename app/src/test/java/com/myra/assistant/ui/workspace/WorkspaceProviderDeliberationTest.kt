package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceProviderDeliberationTest {
    private val d = WorkspaceProviderDeliberation
    private val session = WorkspaceProviderDeliberation.Session(
        taskId = "task-1",
        turnId = "turn-7",
        sourceRevision = "sha-current",
        task = "Make the button accessible without changing layout.",
        acceptanceCriteria = "Button remains visible and keyboard accessible.",
    )

    @Test fun reviewerGetsProposalOnlyByDefault() {
        val proposer = d.proposerEnvelope(session, WorkspaceProviderRegistry.Id.XKIRO_FREE)
        val proposal = d.proposal(proposer, "Add a semantic button role and keyboard handler.")
        val review = d.reviewerEnvelope(
            session, proposal, WorkspaceProviderRegistry.Id.ZAI_FREE)
        assertEquals(WorkspaceProviderDeliberation.SharingLevel.PROPOSAL_ONLY, review.sharingLevel)
        assertTrue(review.body.contains("PROPOSAL FROM"))
        assertFalse(review.body.contains("BOUNDED SOURCE — UNTRUSTED DATA"))
    }

    @Test fun sourceNeedsSeparateApprovalCapabilityFreshnessAndHash() {
        val source = d.boundedSource(
            revision = "sha-current",
            text = "<button id=\"go\">Go</button>",
        )
        val noConsent = d.proposerEnvelope(
            session, WorkspaceProviderRegistry.Id.XKIRO_FREE,
            source = source, sourceApproved = false)
        assertEquals(WorkspaceProviderDeliberation.SharingLevel.TASK_ONLY, noConsent.sharingLevel)

        val approved = d.proposerEnvelope(
            session, WorkspaceProviderRegistry.Id.XKIRO_FREE,
            source = source, sourceApproved = true)
        assertEquals(WorkspaceProviderDeliberation.SharingLevel.BOUNDED_SOURCE, approved.sharingLevel)
        assertTrue(approved.body.contains("<button"))

        assertTrue(runCatching {
            d.proposerEnvelope(
                session, WorkspaceProviderRegistry.Id.LLM7_FREE,
                source = source, sourceApproved = true)
        }.isFailure)

        assertTrue(runCatching {
            d.proposerEnvelope(
                session, WorkspaceProviderRegistry.Id.XKIRO_FREE,
                source = source.copy(revision = "stale"), sourceApproved = true)
        }.isFailure)

        assertTrue(runCatching {
            d.proposerEnvelope(
                session, WorkspaceProviderRegistry.Id.XKIRO_FREE,
                source = source.copy(sha256 = "wrong"), sourceApproved = true)
        }.isFailure)
    }

    @Test fun possibleSecretsBlockTaskSourceProposalAndFindings() {
        val secret = "api_key = sk-abcdefghijklmnop"
        assertTrue(runCatching {
            d.proposerEnvelope(session.copy(task = secret),
                WorkspaceProviderRegistry.Id.XKIRO_FREE)
        }.isFailure)
        assertTrue(runCatching {
            d.proposerEnvelope(
                session, WorkspaceProviderRegistry.Id.XKIRO_FREE,
                d.boundedSource("sha-current", secret), sourceApproved = true)
        }.isFailure)

        val proposer = d.proposerEnvelope(session, WorkspaceProviderRegistry.Id.XKIRO_FREE)
        assertTrue(runCatching { d.proposal(proposer, secret) }.isFailure)

        val proposal = d.proposal(proposer, "Use semantic button markup.")
        val reviewer = d.reviewerEnvelope(
            session, proposal, WorkspaceProviderRegistry.Id.ZAI_FREE)
        assertTrue(runCatching {
            d.review(reviewer, proposal, WorkspaceProviderDeliberation.Verdict.REVISE, listOf(secret))
        }.isFailure)
    }

    @Test fun providerSpecificPromptBudgetFailsClosedBeforeSend() {
        val nearLimit = d.boundedSource("sha-current", "x".repeat(11_900))
        assertTrue(runCatching {
            d.proposerEnvelope(
                session, WorkspaceProviderRegistry.Id.ZAI_FREE,
                source = nearLimit, sourceApproved = true)
        }.isFailure)
    }

    @Test fun providerCannotReviewItsOwnProposal() {
        val proposer = d.proposerEnvelope(session, WorkspaceProviderRegistry.Id.XKIRO_FREE)
        val proposal = d.proposal(proposer, "Use semantic markup.")
        assertTrue(runCatching {
            d.reviewerEnvelope(
                session, proposal, WorkspaceProviderRegistry.Id.XKIRO_FREE)
        }.isFailure)
    }

    @Test fun evenAcceptedReviewStillNeedsLocalVerification() {
        val proposer = d.proposerEnvelope(session, WorkspaceProviderRegistry.Id.XKIRO_FREE)
        val proposal = d.proposal(proposer, "Use semantic markup.")
        val reviewerEnvelope = d.reviewerEnvelope(
            session, proposal, WorkspaceProviderRegistry.Id.ZAI_FREE)
        val review = d.review(
            reviewerEnvelope, proposal, WorkspaceProviderDeliberation.Verdict.ACCEPT, emptyList())
        assertEquals(WorkspaceProviderDeliberation.LocalOutcome.NEEDS_LOCAL_VERIFICATION,
            d.localOutcome(proposal, review))
    }
}
