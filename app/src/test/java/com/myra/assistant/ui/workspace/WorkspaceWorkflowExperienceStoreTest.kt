package com.myra.assistant.ui.workspace

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWorkflowExperienceStoreTest {
    private val sha = "1234567890abcdef1234567890abcdef12345678"

    private fun receipt(task: String? = "Add safe workflow learning") =
        WorkspaceRecentGitHubActionReceipt.Receipt(
            repository = "spy626/myra-android",
            branch = "agent/myra-phase-1",
            userTask = task,
            commitSha = sha,
            files = listOf(
                "app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceActivity.kt"
            ),
            ciRunNumber = 3348L,
            ciStatus = "completed",
            ciConclusion = "success",
            ciUrl = "https://github.com/spy626/myra-android/actions/runs/55",
            completedAtMs = 1234L,
        )

    @Test fun verifiedGithubReceiptBecomesStructuredDeterministicExperience() {
        val record = WorkspaceWorkflowExperience.fromVerifiedGitHub(receipt())

        assertEquals("github:$sha", record.id)
        assertEquals(
            WorkspaceSkillImprovementEvidence.Kind.DETERMINISTIC_VERIFICATION,
            record.evidenceKind,
        )
        assertEquals(
            WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
            record.evidenceSignal,
        )
        assertEquals("ci:3348", record.verificationRef)
        assertEquals(WorkspaceWorkflowExperience.Outcome.VERIFIED_SUCCESS, record.outcome)
        assertTrue(record.capabilities.contains("PROTECTED_FEATURE_BRANCH_WRITE"))
    }

    @Test fun possibleSecretTaskIsRedactedFromWorkflowExperience() {
        val record = WorkspaceWorkflowExperience.fromVerifiedGitHub(
            receipt("api_key=sk-12345678901234567890 use this key")
        )

        assertNull(record.userTask)
        assertFalse(WorkspaceWorkflowExperience.toJson(record).toString()
            .contains("sk-12345678901234567890"))
    }

    @Test fun localStoreIsIdempotentAndVerifiesReopenedRecord() {
        val root = Files.createTempDirectory("workflow-experience-test").toFile()
        try {
            val store = WorkspaceWorkflowExperienceStore(root)
            val record = WorkspaceWorkflowExperience.fromVerifiedGitHub(receipt())

            assertEquals(record, store.record(record))
            assertEquals(record, store.record(record))
            assertEquals(listOf(record), store.list())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun storeBoundsHistoryToLatestSixtyFourVerifiedExecutions() {
        val root = Files.createTempDirectory("workflow-experience-bound").toFile()
        try {
            val store = WorkspaceWorkflowExperienceStore(root)
            repeat(70) { index ->
                val commit = index.toString(16).padStart(40, '0')
                val record = WorkspaceWorkflowExperience.Record(
                    id = "github:$commit",
                    kind = WorkspaceWorkflowExperience.Kind.CONNECTED_GITHUB_SELF_EDIT,
                    userTask = "verified task $index",
                    repository = "spy626/myra-android",
                    branch = "agent/myra-phase-1",
                    commitSha = commit,
                    changedFiles = listOf(
                        "app/src/main/java/com/myra/assistant/ui/workspace/File$index.kt"
                    ),
                    capabilities = listOf(
                        "CONNECTED_REPOSITORY_READ",
                        "PROTECTED_FEATURE_BRANCH_WRITE",
                        "GITHUB_ACTIONS_CI_VERIFY",
                    ),
                    evidenceKind =
                        WorkspaceSkillImprovementEvidence.Kind.DETERMINISTIC_VERIFICATION,
                    evidenceSignal =
                        WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
                    verificationRef = "ci:${1000 + index}",
                    verificationUrl =
                        "https://github.com/spy626/myra-android/actions/runs/${1000 + index}",
                    outcome = WorkspaceWorkflowExperience.Outcome.VERIFIED_SUCCESS,
                    capturedAtMs = index.toLong(),
                )
                store.record(record)
            }

            val saved = store.list()
            assertEquals(64, saved.size)
            assertEquals("github:" + 6.toString(16).padStart(40, '0'), saved.first().id)
            assertEquals("github:" + 69.toString(16).padStart(40, '0'), saved.last().id)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun groundedWorkflowFeedbackPersistsIdempotentlyAgainstVerifiedTarget() {
        val root = Files.createTempDirectory("workflow-feedback-test").toFile()
        try {
            val store = WorkspaceWorkflowExperienceStore(root)
            val experience = WorkspaceWorkflowExperience.fromVerifiedGitHub(receipt())
            store.record(experience)
            val feedback = WorkspaceWorkflowFeedback.fromUserTurn(
                targetExperienceId = experience.id,
                decision = WorkspaceWorkflowFeedbackIntent.Decision(
                    WorkspaceWorkflowFeedbackIntent.Kind.CONFIRM,
                    0.95,
                ),
                sourceTurnId = "turn-1",
                userText = "haan sahi tha",
                capturedAtMs = 2000L,
            )

            assertEquals(feedback, store.recordFeedback(feedback))
            assertEquals(feedback, store.recordFeedback(feedback))
            assertEquals(listOf(feedback), store.listFeedback())
            assertEquals(
                WorkspaceSkillImprovementEvidence.Kind.USER_CONFIRMED,
                feedback.kind,
            )
            assertEquals(
                WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
                feedback.signal,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun correctionFeedbackIsCounterEvidenceAndSecretTextIsRedacted() {
        val feedback = WorkspaceWorkflowFeedback.fromUserTurn(
            targetExperienceId = "github:" + sha,
            decision = WorkspaceWorkflowFeedbackIntent.Decision(
                WorkspaceWorkflowFeedbackIntent.Kind.CORRECT,
                0.96,
            ),
            sourceTurnId = "turn-2",
            userText = "api_key=sk-12345678901234567890 ye galat tha",
            capturedAtMs = 2001L,
        )

        assertEquals(
            WorkspaceSkillImprovementEvidence.Kind.USER_CORRECTED,
            feedback.kind,
        )
        assertEquals(
            WorkspaceSkillImprovementEvidence.Signal.COUNTER_EVIDENCE,
            feedback.signal,
        )
        assertNull(feedback.feedbackText)
    }

    @Test fun executionRecoveryStatsRoundTripWithVerifiedExperience() {
        val record = WorkspaceWorkflowExperience.fromVerifiedGitHub(
            receipt(),
            WorkspaceGitHubSelfEditFlow.ExecutionSummary(
                providerCalls = 4,
                reviewCalls = 2,
                fallbackSwitches = 1,
                ciRepairs = 1,
                commitAttempts = 2,
            ),
        )
        val decoded = WorkspaceWorkflowExperience.fromJson(
            WorkspaceWorkflowExperience.toJson(record)
        )

        assertEquals(record, decoded)
        assertEquals(4, decoded?.providerCalls)
        assertEquals(2, decoded?.reviewCalls)
        assertEquals(1, decoded?.fallbackSwitches)
        assertEquals(1, decoded?.ciRepairs)
        assertEquals(2, decoded?.commitAttempts)
    }
}
