package com.myra.assistant.ui.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWorkflowImprovementApprovalRuntimeTest {
    @Test fun runtimeProjectsExactProposalAndRecordedApprovalAsConsentOnly() {
        val proposal = WorkspaceWorkflowImprovementProposal.fromCandidate(
            WorkspaceWorkflowImprovementGate.Candidate(
                signatureSha256 = "e".repeat(64),
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
        val approval = WorkspaceWorkflowImprovementApproval.fromUserTurn(
            proposal = proposal,
            sourceTurnId = "approve-turn",
            approvedAtMs = 31L,
        )
        val text = WorkspaceRuntimeSelfModel.instructions(
            WorkspaceRuntimeSelfModel.Snapshot(
                github = WorkspaceRuntimeSelfModel.GitHubState(connected = true),
                workflowImprovementProposals = listOf(proposal),
                workflowImprovementApprovals = listOf(approval),
            )
        )

        assertTrue(text.contains("WORKFLOW IMPROVEMENT PROPOSALS"))
        assertTrue(text.contains(proposal.id))
        assertTrue(text.contains("RECORDED_FOR_THIS_EXACT_PROPOSAL"))
        assertTrue(text.contains("consent for one exact Proposal ID only"))
        assertTrue(text.contains("It is not activation"))
        assertTrue(text.contains("old approval must not authorize the new proposal"))
    }
}
