package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceProviderDeliberationRunTest {
    private fun approvedTask(
        goal: String = "Improve the current button safely.",
        criteria: String = "No unrelated behavior changes.",
        revision: String = "rev-1",
    ): WorkspaceTask {
        val base = WorkspaceTask(
            projectId = "project-1",
            taskId = "task-1",
            goal = goal,
            status = WorkspaceTaskStatus.DRAFT,
            createdAtMs = 10L,
            updatedAtMs = 20L,
            acceptanceCriteria = criteria,
            specRevision = revision,
        )
        val token = WorkspaceTaskContract.specToken(base)
        return base.copy(approvedSpecToken = token, approvedAtMs = 20L)
    }

    @Test fun oneProposerThenDifferentReviewerEndsAtLocalVerification() {
        val task = approvedTask()
        var state = WorkspaceProviderDeliberationRun.start(
            task, "turn-1", "source-1",
            WorkspaceProviderRegistry.Id.XKIRO_FREE,
            WorkspaceProviderRegistry.Id.ZAI_FREE)

        val proposer = WorkspaceProviderDeliberationRun.prepareProposer(
            state, task, "turn-1", "source-1")
        assertEquals(WorkspaceProviderDeliberationRun.Phase.AWAITING_PROPOSAL,
            proposer.state.phase)
        state = WorkspaceProviderDeliberationRun.acceptProposal(
            proposer.state, task, "turn-1", "source-1",
            "Use a semantic button without changing layout.")
        assertEquals(WorkspaceProviderDeliberationRun.Phase.READY_FOR_REVIEWER,
            state.phase)

        val reviewer = WorkspaceProviderDeliberationRun.prepareReviewer(
            state, task, "turn-1", "source-1")
        assertEquals(WorkspaceProviderDeliberation.SharingLevel.PROPOSAL_ONLY,
            reviewer.envelope.sharingLevel)

        state = WorkspaceProviderDeliberationRun.acceptReview(
            reviewer.state, task, "turn-1", "source-1",
            """{"verdict":"ACCEPT","findings":[]}""")
        assertEquals(WorkspaceProviderDeliberationRun.Phase.NEEDS_LOCAL_VERIFICATION,
            state.phase)
        assertEquals(WorkspaceProviderDeliberation.Verdict.ACCEPT, state.review?.verdict)
    }

    @Test fun staleTaskTurnOrSourceRejectsLaterModelOutput() {
        val task = approvedTask()
        val started = WorkspaceProviderDeliberationRun.start(
            task, "turn-1", "source-1",
            WorkspaceProviderRegistry.Id.XKIRO_FREE,
            WorkspaceProviderRegistry.Id.ZAI_FREE)
        val dispatched = WorkspaceProviderDeliberationRun.prepareProposer(
            started, task, "turn-1", "source-1").state

        val changedTask = approvedTask(goal = "Different goal", revision = "rev-2")
        assertTrue(runCatching {
            WorkspaceProviderDeliberationRun.acceptProposal(
                dispatched, changedTask, "turn-1", "source-1", "proposal")
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceProviderDeliberationRun.acceptProposal(
                dispatched, task, "turn-2", "source-1", "proposal")
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceProviderDeliberationRun.acceptProposal(
                dispatched, task, "turn-1", "source-2", "proposal")
        }.isFailure)
    }

    @Test fun pausedOrUnapprovedTaskCannotStart() {
        val approved = approvedTask()
        assertTrue(runCatching {
            WorkspaceProviderDeliberationRun.start(
                approved.copy(status = WorkspaceTaskStatus.PAUSED),
                "turn", "source",
                WorkspaceProviderRegistry.Id.XKIRO_FREE,
                WorkspaceProviderRegistry.Id.ZAI_FREE)
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceProviderDeliberationRun.start(
                approved.copy(approvedSpecToken = null, approvedAtMs = null),
                "turn", "source",
                WorkspaceProviderRegistry.Id.XKIRO_FREE,
                WorkspaceProviderRegistry.Id.ZAI_FREE)
        }.isFailure)
    }

    @Test fun reviewerJsonIsStrictAndBounded() {
        val valid = WorkspaceProviderDeliberationRun.parseReview(
            """{"verdict":"REVISE","findings":["Missing stale-source check"]}""")
        assertEquals(WorkspaceProviderDeliberation.Verdict.REVISE, valid.first)
        assertEquals(1, valid.second.size)

        listOf(
            "looks good",
            """{"verdict":"YES","findings":[]}""",
            """{"verdict":"ACCEPT","findings":[],"extra":"x"}""",
            """{"verdict":"ACCEPT","findings":"none"}"""
        ).forEach { raw ->
            assertTrue("Unsafe review accepted: $raw", runCatching {
                WorkspaceProviderDeliberationRun.parseReview(raw)
            }.isFailure)
        }
    }

    @Test fun nonCodingProviderCannotTakeAReviewSeat() {
        val task = approvedTask()
        assertTrue(runCatching {
            WorkspaceProviderDeliberationRun.start(
                task, "turn", "source",
                WorkspaceProviderRegistry.Id.XKIRO_FREE,
                WorkspaceProviderRegistry.Id.LLM7_FREE)
        }.isFailure)
    }
}
