package com.myra.assistant.ui.workspace

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceWorkflowImprovementApprovalStoreTest {
    private fun proposal() = WorkspaceWorkflowImprovementProposal.fromCandidate(
        WorkspaceWorkflowImprovementGate.Candidate(
            signatureSha256 = "d".repeat(64),
            status =
                WorkspaceWorkflowImprovementGate.Status.READY_FOR_MANUAL_IMPROVEMENT_PROPOSAL,
            kind = WorkspaceWorkflowExperience.Kind.CONNECTED_GITHUB_SELF_EDIT,
            repository = "spy626/myra-android",
            branch = "agent/myra-phase-1",
            capabilities = listOf(
                "CONNECTED_REPOSITORY_READ",
                "PROTECTED_FEATURE_BRANCH_WRITE",
                "GITHUB_ACTIONS_CI_VERIFY",
            ),
            constraints = listOf(
                "CURRENT_TURN_AUTHORITY_REQUIRED",
                "FEATURE_BRANCH_ONLY",
                "MAIN_MASTER_FORBIDDEN",
                "EXACT_CI_GREEN_REQUIRED",
                "CI_IS_NOT_PHONE_PASS",
            ),
            verifiedExecutions = 2,
            userSupportedExecutions = 2,
            evidenceRefs = listOf("ci:3362", "ci:3360"),
            recoverySignals = emptyList(),
            lastReflectedAtMs = 10L,
        )
    )

    @Test fun exactProposalApprovalPersistsIdempotentlyInExistingExperienceOwner() {
        val root = Files.createTempDirectory("workflow-approval-store").toFile()
        try {
            val store = WorkspaceWorkflowExperienceStore(root)
            val approval = WorkspaceWorkflowImprovementApproval.fromUserTurn(
                proposal = proposal(),
                sourceTurnId = "approval-turn",
                approvedAtMs = 30L,
            )

            assertEquals(approval, store.recordApproval(approval))
            assertEquals(approval, store.recordApproval(approval))
            assertEquals(listOf(approval), store.listApprovals())
        } finally {
            root.deleteRecursively()
        }
    }
}
