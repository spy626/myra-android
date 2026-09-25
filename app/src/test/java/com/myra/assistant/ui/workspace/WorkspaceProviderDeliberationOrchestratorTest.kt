package com.myra.assistant.ui.workspace

import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WorkspaceProviderDeliberationOrchestratorTest {
    private fun task(): WorkspaceTask {
        val base = WorkspaceTask(
            projectId = "p1",
            taskId = "t1",
            goal = "Improve one bounded file.",
            status = WorkspaceTaskStatus.DRAFT,
            createdAtMs = 10L,
            updatedAtMs = 20L,
            acceptanceCriteria = "No unrelated changes.",
            specRevision = "rev1",
        )
        return base.copy(
            approvedSpecToken = WorkspaceTaskContract.specToken(base),
            approvedAtMs = 20L,
        )
    }

    private fun snapshot() = WorkspaceProviderDeliberationOrchestrator.Snapshot(
        task = task(),
        turnId = "turn1",
        sourceRevision = "source1",
    )

    private fun xKiro(sourceApproved: Boolean = false) =
        WorkspaceProviderDeliberationOrchestrator.Access(
            provider = WorkspaceProviderRegistry.Id.XKIRO_FREE,
            key = "xkiro-key",
            enabled = true,
            sourceApproved = sourceApproved,
        )

    private fun zai(sourceApproved: Boolean = false) =
        WorkspaceProviderDeliberationOrchestrator.Access(
            provider = WorkspaceProviderRegistry.Id.ZAI_FREE,
            key = "zai-key",
            enabled = true,
            sourceApproved = sourceApproved,
        )

    @After fun clearHealth() = WorkspaceProviderSessionHealth.clearForTests()

    @Test fun oneProposerThenOneReviewerIsNetworkReadyButStillReadOnly() {
        val current = snapshot()
        val first = WorkspaceProviderDeliberationOrchestrator.start(
            current, xKiro(), zai())
        assertEquals(WorkspaceProviderRegistry.Id.XKIRO_FREE, first.dispatch.provider)
        assertEquals(WorkspaceProviderDeliberationRun.Phase.AWAITING_PROPOSAL,
            first.state.phase)

        val second = WorkspaceProviderDeliberationOrchestrator
            .acceptProposerAndPrepareReviewer(
                first, current, "Use a semantic button.", zai())
        assertEquals(WorkspaceProviderRegistry.Id.ZAI_FREE, second.dispatch.provider)
        assertEquals(WorkspaceProviderDeliberationRun.Phase.AWAITING_REVIEW,
            second.state.phase)

        val final = WorkspaceProviderDeliberationOrchestrator.acceptReviewer(
            second, current, """{"verdict":"ACCEPT","findings":[]}""")
        assertEquals(WorkspaceProviderDeliberationRun.Phase.NEEDS_LOCAL_VERIFICATION,
            final.phase)
        assertEquals(WorkspaceProviderDeliberation.Verdict.ACCEPT, final.review?.verdict)
    }

    @Test fun sourcePermissionDoesNotTransferBetweenProviders() {
        val current = snapshot()
        val source = WorkspaceProviderDeliberation.boundedSource(
            "source1", "fun greet() = \"hello\"")

        val first = WorkspaceProviderDeliberationOrchestrator.start(
            current, xKiro(sourceApproved = false), zai(sourceApproved = true), source)
        assertEquals(WorkspaceProviderDeliberation.SharingLevel.TASK_ONLY,
            first.state.proposerEnvelope?.sharingLevel)

        val second = WorkspaceProviderDeliberationOrchestrator
            .acceptProposerAndPrepareReviewer(
                first, current, "Keep the change bounded.",
                zai(sourceApproved = true), source)
        assertEquals(WorkspaceProviderDeliberation.SharingLevel.BOUNDED_SOURCE,
            second.state.reviewerEnvelope?.sharingLevel)
        assertTrue(second.state.reviewerEnvelope?.body.orEmpty().contains("fun greet"))
    }

    @Test fun keysStayOutOfStateAndPromptEnvelopes() {
        val first = WorkspaceProviderDeliberationOrchestrator.start(
            snapshot(), xKiro(), zai())
        assertFalse(first.state.proposerEnvelope?.body.orEmpty().contains("xkiro-key"))
        assertFalse(first.state.toString().contains("xkiro-key"))

        val buffer = Buffer()
        requireNotNull(first.dispatch.request.body).writeTo(buffer)
        assertFalse(buffer.readUtf8().contains("xkiro-key"))
        assertEquals("Bearer xkiro-key",
            first.dispatch.request.header("Authorization"))
    }

    @Test fun staleSnapshotPreventsReviewerDispatch() {
        val current = snapshot()
        val first = WorkspaceProviderDeliberationOrchestrator.start(
            current, xKiro(), zai())
        assertTrue(runCatching {
            WorkspaceProviderDeliberationOrchestrator.acceptProposerAndPrepareReviewer(
                first, current.copy(sourceRevision = "changed"),
                "Proposal", zai())
        }.isFailure)
    }

    @Test fun seatMismatchAndCooldownFailBeforeDispatch() {
        val current = snapshot()
        assertTrue(runCatching {
            WorkspaceProviderDeliberationOrchestrator.start(
                current, xKiro(), xKiro())
        }.isFailure)

        WorkspaceProviderSessionHealth.recordHttp(
            WorkspaceProviderRegistry.Id.XKIRO_FREE, 429, "60", nowMs = 1_000L)
        assertTrue(runCatching {
            WorkspaceProviderDeliberationOrchestrator.start(
                current, xKiro(), zai())
        }.isFailure)
    }

    @Test fun reviewerMustUseItsOwnIndependentAccessRecord() {
        val current = snapshot()
        val first = WorkspaceProviderDeliberationOrchestrator.start(
            current, xKiro(), zai())
        assertTrue(runCatching {
            WorkspaceProviderDeliberationOrchestrator.acceptProposerAndPrepareReviewer(
                first, current, "Proposal", xKiro())
        }.isFailure)
    }
}
