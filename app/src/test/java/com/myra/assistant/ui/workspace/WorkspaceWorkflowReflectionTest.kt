package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWorkflowReflectionTest {
    private fun experience(
        index: Int,
        providerCalls: Int = 2,
        reviewCalls: Int = 1,
        fallbackSwitches: Int = 0,
        ciRepairs: Int = 0,
        commitAttempts: Int = 1,
    ): WorkspaceWorkflowExperience.Record {
        val commit = index.toString(16).padStart(40, '0')
        return WorkspaceWorkflowExperience.Record(
            id = "github:" + commit,
            kind = WorkspaceWorkflowExperience.Kind.CONNECTED_GITHUB_SELF_EDIT,
            userTask = "verified task " + index,
            repository = "spy626/myra-android",
            branch = "agent/myra-phase-1",
            commitSha = commit,
            changedFiles = listOf(
                "app/src/main/java/com/myra/assistant/ui/workspace/File" + index + ".kt"
            ),
            capabilities = listOf(
                "CONNECTED_REPOSITORY_READ",
                "PROTECTED_FEATURE_BRANCH_WRITE",
                "GITHUB_ACTIONS_CI_VERIFY",
            ),
            evidenceKind = WorkspaceSkillImprovementEvidence.Kind.DETERMINISTIC_VERIFICATION,
            evidenceSignal = WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
            verificationRef = "ci:" + (3350 + index),
            verificationUrl =
                "https://github.com/spy626/myra-android/actions/runs/" + (3350 + index),
            outcome = WorkspaceWorkflowExperience.Outcome.VERIFIED_SUCCESS,
            capturedAtMs = index.toLong(),
            providerCalls = providerCalls,
            reviewCalls = reviewCalls,
            fallbackSwitches = fallbackSwitches,
            ciRepairs = ciRepairs,
            commitAttempts = commitAttempts,
        )
    }

    private fun feedback(
        target: WorkspaceWorkflowExperience.Record,
        kind: WorkspaceWorkflowFeedbackIntent.Kind,
        at: Long,
    ): WorkspaceWorkflowFeedback.Record =
        WorkspaceWorkflowFeedback.fromUserTurn(
            targetExperienceId = target.id,
            decision = WorkspaceWorkflowFeedbackIntent.Decision(kind, 0.96),
            sourceTurnId = "turn-" + at,
            userText = when (kind) {
                WorkspaceWorkflowFeedbackIntent.Kind.CONFIRM -> "haan sahi tha"
                WorkspaceWorkflowFeedbackIntent.Kind.CORRECT -> "ye galat tha"
                WorkspaceWorkflowFeedbackIntent.Kind.UNDO -> "undo last change"
            },
            capturedAtMs = at,
        )

    @Test fun verifiedExecutionReflectsStructuredOutcomeWithoutReasoning() {
        val record = experience(
            index = 1,
            providerCalls = 4,
            reviewCalls = 2,
            fallbackSwitches = 1,
            ciRepairs = 1,
            commitAttempts = 2,
        )

        val reflection = WorkspaceWorkflowReflection.reflect(
            experiences = listOf(record),
            feedback = emptyList(),
        ).single()

        assertEquals(WorkspaceWorkflowReflection.FeedbackState.NONE, reflection.feedbackState)
        assertEquals(WorkspaceWorkflowReflection.Disposition.OBSERVE_ONLY, reflection.disposition)
        assertEquals(1, reflection.ciRepairs)
        assertEquals(2, reflection.commitAttempts)
        assertTrue(reflection.recoverySignals.contains("PROVIDER_FALLBACK_USED"))
        assertTrue(reflection.recoverySignals.contains("CI_REPAIR_USED"))
        assertTrue(reflection.recoverySignals.contains("MULTIPLE_COMMIT_ATTEMPTS"))
        assertTrue(reflection.constraints.contains("CURRENT_TURN_AUTHORITY_REQUIRED"))
        assertTrue(reflection.constraints.contains("CI_IS_NOT_PHONE_PASS"))
    }

    @Test fun latestGroundedConfirmationProducesUserSupportedDisposition() {
        val record = experience(2)
        val reflection = WorkspaceWorkflowReflection.reflect(
            experiences = listOf(record),
            feedback = listOf(
                feedback(record, WorkspaceWorkflowFeedbackIntent.Kind.CONFIRM, 10L)
            ),
        ).single()

        assertEquals(
            WorkspaceWorkflowReflection.FeedbackState.CONFIRMED,
            reflection.feedbackState,
        )
        assertEquals(
            WorkspaceWorkflowReflection.Disposition.USER_SUPPORTED,
            reflection.disposition,
        )
    }

    @Test fun latestGroundedCorrectionOrUndoBlocksImprovementDisposition() {
        val record = experience(3)
        listOf(
            WorkspaceWorkflowFeedbackIntent.Kind.CORRECT,
            WorkspaceWorkflowFeedbackIntent.Kind.UNDO,
        ).forEach { kind ->
            val reflection = WorkspaceWorkflowReflection.reflect(
                experiences = listOf(record),
                feedback = listOf(feedback(record, kind, 20L)),
            ).single()

            assertEquals(
                WorkspaceWorkflowReflection.Disposition.BLOCKED_BY_COUNTER_EVIDENCE,
                reflection.disposition,
            )
        }
    }

    @Test fun reflectionPromptIsEvidenceOnlyAndNeverImprovementAuthority() {
        val text = WorkspaceWorkflowReflection.instructions(
            WorkspaceWorkflowReflection.reflect(
                experiences = listOf(experience(4)),
                feedback = emptyList(),
            )
        )

        assertTrue(text.contains("STRUCTURED WORKFLOW REFLECTIONS"))
        assertTrue(text.contains("never hidden reasoning or action authority"))
        assertTrue(text.contains("do not auto-edit"))
        assertTrue(text.contains("separate improvement gate"))
    }
}
