package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWorkflowImprovementGateTest {
    private fun reflection(
        index: Int,
        disposition: WorkspaceWorkflowReflection.Disposition,
        feedbackState: WorkspaceWorkflowReflection.FeedbackState,
        extraCapability: String? = null,
    ): WorkspaceWorkflowReflection.Record {
        val capabilities = mutableListOf(
            "CONNECTED_REPOSITORY_READ",
            "PROTECTED_FEATURE_BRANCH_WRITE",
            "GITHUB_ACTIONS_CI_VERIFY",
        )
        extraCapability?.let(capabilities::add)
        return WorkspaceWorkflowReflection.Record(
            experienceId = "github:" + index.toString(16).padStart(40, '0'),
            intent = "verified task " + index,
            kind = WorkspaceWorkflowExperience.Kind.CONNECTED_GITHUB_SELF_EDIT,
            repository = "spy626/myra-android",
            branch = "agent/myra-phase-1",
            capabilities = capabilities,
            constraints = listOf(
                "CURRENT_TURN_AUTHORITY_REQUIRED",
                "FEATURE_BRANCH_ONLY",
                "MAIN_MASTER_FORBIDDEN",
                "EXACT_CI_GREEN_REQUIRED",
                "CI_IS_NOT_PHONE_PASS",
            ),
            verificationRef = "ci:" + (3358 + index),
            outcome = WorkspaceWorkflowExperience.Outcome.VERIFIED_SUCCESS,
            providerCalls = 2,
            reviewCalls = 1,
            fallbackSwitches = if (index % 2 == 0) 1 else 0,
            ciRepairs = if (index % 3 == 0) 1 else 0,
            commitAttempts = if (index % 3 == 0) 2 else 1,
            recoverySignals = buildList {
                if (index % 2 == 0) add("PROVIDER_FALLBACK_USED")
                if (index % 3 == 0) add("CI_REPAIR_USED")
                if (index % 3 == 0) add("MULTIPLE_COMMIT_ATTEMPTS")
            },
            feedbackState = feedbackState,
            feedbackRef = when (feedbackState) {
                WorkspaceWorkflowReflection.FeedbackState.NONE -> null
                else -> "feedback:" + index.toString(16).padStart(64, '0')
            },
            disposition = disposition,
            reflectedAtMs = index.toLong(),
        )
    }

    private fun supported(index: Int) = reflection(
        index,
        WorkspaceWorkflowReflection.Disposition.USER_SUPPORTED,
        WorkspaceWorkflowReflection.FeedbackState.CONFIRMED,
    )

    @Test fun twoDistinctUserSupportedVerifiedExecutionsCreateCandidate() {
        val candidates = WorkspaceWorkflowImprovementGate.evaluate(
            listOf(supported(1), supported(2))
        )

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals(
            WorkspaceWorkflowImprovementGate.Status.READY_FOR_MANUAL_IMPROVEMENT_PROPOSAL,
            candidate.status,
        )
        assertEquals(2, candidate.verifiedExecutions)
        assertEquals(2, candidate.userSupportedExecutions)
        assertEquals(listOf("ci:3360", "ci:3359"), candidate.evidenceRefs)
    }

    @Test fun oneUserSupportIsNotEnoughEvenWithRepeatedVerifiedExecutions() {
        val observe = reflection(
            2,
            WorkspaceWorkflowReflection.Disposition.OBSERVE_ONLY,
            WorkspaceWorkflowReflection.FeedbackState.NONE,
        )
        val candidates = WorkspaceWorkflowImprovementGate.evaluate(
            listOf(supported(1), observe)
        )

        assertTrue(candidates.isEmpty())
    }

    @Test fun anyCounterEvidenceInSameWorkflowFamilyBlocksCandidate() {
        val corrected = reflection(
            3,
            WorkspaceWorkflowReflection.Disposition.BLOCKED_BY_COUNTER_EVIDENCE,
            WorkspaceWorkflowReflection.FeedbackState.CORRECTED,
        )
        val candidates = WorkspaceWorkflowImprovementGate.evaluate(
            listOf(supported(1), supported(2), corrected)
        )

        assertTrue(candidates.isEmpty())
    }

    @Test fun differentWorkflowMechanicsDoNotCombineSupportCounts() {
        val different = reflection(
            2,
            WorkspaceWorkflowReflection.Disposition.USER_SUPPORTED,
            WorkspaceWorkflowReflection.FeedbackState.CONFIRMED,
            extraCapability = "VERIFY_ONLY_EXTRA",
        )
        val candidates = WorkspaceWorkflowImprovementGate.evaluate(
            listOf(supported(1), different)
        )

        assertTrue(candidates.isEmpty())
    }

    @Test fun candidatePromptNeverClaimsAutomaticPromotionOrExecution() {
        val candidate = WorkspaceWorkflowImprovementGate.evaluate(
            listOf(supported(1), supported(2))
        ).single()
        val text = WorkspaceWorkflowImprovementGate.instructions(listOf(candidate))

        assertTrue(text.contains("NEVER execution/promotion authority"))
        assertTrue(text.contains("does not apply code"))
        assertTrue(text.contains("does not alter a skill"))
        assertTrue(text.contains("Any grounded correction/undo blocks candidacy"))
    }
}
