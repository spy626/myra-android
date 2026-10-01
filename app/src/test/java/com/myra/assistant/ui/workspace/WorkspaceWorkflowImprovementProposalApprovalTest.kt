package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWorkflowImprovementProposalApprovalTest {
    private fun candidate(
        refs: List<String> = listOf("ci:3362", "ci:3360"),
        verified: Int = 2,
        supported: Int = 2,
    ) = WorkspaceWorkflowImprovementGate.Candidate(
        signatureSha256 = "a".repeat(64),
        status = WorkspaceWorkflowImprovementGate.Status.READY_FOR_MANUAL_IMPROVEMENT_PROPOSAL,
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
        verifiedExecutions = verified,
        userSupportedExecutions = supported,
        evidenceRefs = refs,
        recoverySignals = listOf("CI_REPAIR_USED"),
        lastReflectedAtMs = 10L,
    )

    @Test fun proposalIsStableHumanReadableAndEvidenceBound() {
        val first = WorkspaceWorkflowImprovementProposal.fromCandidate(candidate())
        val same = WorkspaceWorkflowImprovementProposal.fromCandidate(candidate())
        val changed = WorkspaceWorkflowImprovementProposal.fromCandidate(
            candidate(
                refs = listOf("ci:3364", "ci:3362", "ci:3360"),
                verified = 3,
                supported = 3,
            )
        )

        assertEquals(first, same)
        assertTrue(first.id.startsWith("workflow-proposal:"))
        assertTrue(first.summary.contains("Proposal ID: " + first.id))
        assertTrue(first.summary.contains("does NOT execute anything"))
        assertNotEquals(first.id, changed.id)
        assertNotEquals(first.evidenceSha256, changed.evidenceSha256)
    }

    @Test fun explicitApprovalQuestionDoesNotApproveButCurrentImperativeDoes() {
        assertNull(WorkspaceWorkflowImprovementApprovalIntent.decide(
            "kya main is proposal ko approve karu?"
        ))
        assertNull(WorkspaceWorkflowImprovementApprovalIntent.decide(
            "do not approve this proposal"
        ))
        assertTrue(
            WorkspaceWorkflowImprovementApprovalIntent.decide(
                "haan approve karo"
            ) != null
        )
        assertTrue(
            WorkspaceWorkflowImprovementApprovalIntent.decide(
                "I approve this proposal"
            ) != null
        )
    }

    @Test fun groundingRequiresOneExactVisibleProposal() {
        val first = WorkspaceWorkflowImprovementProposal.fromCandidate(candidate())
        val second = first.copy(
            id = "workflow-proposal:" + "b".repeat(64),
            candidateSignatureSha256 = "b".repeat(64),
            evidenceSha256 = "c".repeat(64),
            summary = first.summary.replace(first.id, "workflow-proposal:" + "b".repeat(64)),
        )

        assertEquals(
            first,
            WorkspaceWorkflowImprovementApprovalGrounding.resolve(
                userText = "haan approve karo",
                recentAssistantTexts = listOf(first.summary),
                proposals = listOf(first, second),
            )
        )
        assertNull(
            WorkspaceWorkflowImprovementApprovalGrounding.resolve(
                userText = "haan approve karo",
                recentAssistantTexts = listOf(first.summary + "\n" + second.summary),
                proposals = listOf(first, second),
            )
        )
        assertEquals(
            second,
            WorkspaceWorkflowImprovementApprovalGrounding.resolve(
                userText = "approve " + second.id,
                recentAssistantTexts = emptyList(),
                proposals = listOf(first, second),
            )
        )
    }

    @Test fun approvalReceiptRecordsConsentOnlyAndNeverActivation() {
        val proposal = WorkspaceWorkflowImprovementProposal.fromCandidate(candidate())
        val approval = WorkspaceWorkflowImprovementApproval.fromUserTurn(
            proposal = proposal,
            sourceTurnId = "turn-approval-1",
            approvedAtMs = 20L,
        )
        val receipt = WorkspaceWorkflowImprovementApproval.receipt(proposal, approval)

        assertTrue(receipt.contains("APPROVAL_RECORDED_ONLY"))
        assertTrue(receipt.contains("Nothing was activated"))
        assertTrue(receipt.contains("no code/skill was changed"))
        assertTrue(receipt.contains("current-turn authority is still required"))
        assertFalse(receipt.contains("activated successfully"))
    }
}
