package com.myra.assistant.ui.workspace

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentReachDeliberationBridgeTest {
    private val sha = "1234567890abcdef1234567890abcdef12345678"
    private val target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")

    private fun approvedTask(): WorkspaceTask {
        val base = WorkspaceTask(
            projectId = "p1",
            taskId = "t1",
            goal = "Evaluate one bounded external repository idea for LYRA.",
            status = WorkspaceTaskStatus.DRAFT,
            createdAtMs = 10L,
            updatedAtMs = 20L,
            acceptanceCriteria = "Use pinned evidence only; do not write files.",
            specRevision = "spec-1",
        )
        return base.copy(
            approvedSpecToken = WorkspaceTaskContract.specToken(base),
            approvedAtMs = 20L,
        )
    }

    private fun evidence(content: String) =
        WorkspaceAgentReachEvidence.create(
            requested = target,
            final = WorkspaceAgentReachPolicy.parse(
                "https://github.com/a/b/blob/$sha/README.md"),
            adapter = "github-public-read",
            content = content,
            fetchedAtMs = 1L,
            revision = sha,
        )

    private fun pack(content: String = "# README\nUse bounded repository evidence.") =
        WorkspaceAgentReachDeliberationEvidence.build(
            WorkspaceAgentReachGitHubRunner.Completion(
                evidence = evidence(content),
                repositoryMeta = null,
                repositoryIndex = null,
            )
        )

    private fun xKiro(sourceApproved: Boolean) =
        WorkspaceProviderDeliberationOrchestrator.Access(
            provider = WorkspaceProviderRegistry.Id.XKIRO_FREE,
            key = "xkiro-key",
            enabled = true,
            sourceApproved = sourceApproved,
        )

    private fun zai(sourceApproved: Boolean) =
        WorkspaceProviderDeliberationOrchestrator.Access(
            provider = WorkspaceProviderRegistry.Id.ZAI_FREE,
            key = "zai-key",
            enabled = true,
            sourceApproved = sourceApproved,
        )

    @After fun clearHealth() = WorkspaceProviderSessionHealth.clearForTests()

    @Test fun providerSharingConsentIsIndependentAcrossSeats() {
        val task = approvedTask()
        val pack = pack()
        val proposer = WorkspaceAgentReachDeliberationBridge.start(
            task = task,
            turnId = "turn-1",
            pack = pack,
            proposer = xKiro(sourceApproved = false),
            reviewer = zai(sourceApproved = true),
        )
        assertEquals(
            WorkspaceProviderDeliberation.SharingLevel.TASK_ONLY,
            proposer.step.state.proposerEnvelope?.sharingLevel,
        )
        assertFalse(proposer.step.state.proposerEnvelope?.body.orEmpty()
            .contains("EXTERNAL GITHUB EVIDENCE"))

        val reviewer = WorkspaceAgentReachDeliberationBridge.acceptProposerAndPrepareReviewer(
            stage = proposer,
            currentTask = task,
            currentTurnId = "turn-1",
            pack = pack,
            rawProposal = "Adopt the bounded structure-first idea only.",
            reviewer = zai(sourceApproved = true),
        )
        assertEquals(
            WorkspaceProviderDeliberation.SharingLevel.BOUNDED_SOURCE,
            reviewer.step.state.reviewerEnvelope?.sharingLevel,
        )
        assertTrue(reviewer.step.state.reviewerEnvelope?.body.orEmpty()
            .contains("EXTERNAL GITHUB EVIDENCE"))

        val final = WorkspaceAgentReachDeliberationBridge.acceptReviewer(
            stage = reviewer,
            currentTask = task,
            currentTurnId = "turn-1",
            pack = pack,
            rawReview = """{"verdict":"ACCEPT","findings":[]}""",
        )
        assertEquals(
            WorkspaceProviderDeliberationRun.Phase.NEEDS_LOCAL_VERIFICATION,
            final.phase,
        )
    }

    @Test fun changedEvidencePackIsRejectedBeforeReviewerDispatch() {
        val task = approvedTask()
        val firstPack = pack("# README\nFirst pinned evidence.")
        val stage = WorkspaceAgentReachDeliberationBridge.start(
            task, "turn-1", firstPack,
            xKiro(sourceApproved = true), zai(sourceApproved = true),
        )
        val changedPack = pack("# README\nDifferent bounded evidence.")
        assertNotEquals(firstPack.sha256, changedPack.sha256)
        assertTrue(runCatching {
            WorkspaceAgentReachDeliberationBridge.acceptProposerAndPrepareReviewer(
                stage, task, "turn-1", changedPack,
                "Proposal", zai(sourceApproved = true))
        }.isFailure)
    }

    @Test fun tamperedPackHashIsRejectedBeforeAnyDispatchPreparation() {
        val task = approvedTask()
        val valid = pack()
        val tampered = valid.copy(text = valid.text + "\nTAMPERED")
        assertTrue(runCatching {
            WorkspaceAgentReachDeliberationBridge.start(
                task, "turn-1", tampered,
                xKiro(sourceApproved = true), zai(sourceApproved = true))
        }.isFailure)
    }

    @Test fun bothSeatsCanRemainProposalOnlyWithoutSharingGithubContent() {
        val task = approvedTask()
        val pack = pack()
        val proposer = WorkspaceAgentReachDeliberationBridge.start(
            task, "turn-1", pack,
            xKiro(sourceApproved = false), zai(sourceApproved = false),
        )
        val reviewer = WorkspaceAgentReachDeliberationBridge.acceptProposerAndPrepareReviewer(
            proposer, task, "turn-1", pack,
            "Use a bounded local idea.", zai(sourceApproved = false),
        )
        assertEquals(
            WorkspaceProviderDeliberation.SharingLevel.PROPOSAL_ONLY,
            reviewer.step.state.reviewerEnvelope?.sharingLevel,
        )
        assertFalse(reviewer.step.state.reviewerEnvelope?.body.orEmpty()
            .contains("EXTERNAL GITHUB EVIDENCE"))
    }
}
