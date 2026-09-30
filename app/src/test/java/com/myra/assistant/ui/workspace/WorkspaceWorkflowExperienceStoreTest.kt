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
}
