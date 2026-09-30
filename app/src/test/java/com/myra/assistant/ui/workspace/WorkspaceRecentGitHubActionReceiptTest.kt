package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRecentGitHubActionReceiptTest {
    private val sha = "1234567890abcdef1234567890abcdef12345678"

    private fun completion() = WorkspaceGitHubSelfEditFlow.Completion(
        commit = WorkspaceGitHubConnector.CommitReceipt(
            repository = "spy626/myra-android",
            branch = "agent/myra-phase-1",
            previousHead = "abcdef1234567890abcdef1234567890abcdef12",
            commitSha = sha,
            files = listOf(
                "app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceActivity.kt",
            ),
        ),
        workflow = WorkspaceGitHubConnector.WorkflowRun(
            id = 44L,
            runNumber = 3342L,
            name = "Build Android APK",
            headSha = sha,
            status = "completed",
            conclusion = "success",
            url = "https://github.com/spy626/myra-android/actions/runs/44",
        ),
        pullRequest = null,
    )

    @Test fun verifiedCompletionRoundTripsAsStructuredProvenance() {
        val receipt = WorkspaceRecentGitHubActionReceipt.fromCompletion(
            completion(),
            "3324 dekho kuch change mat karna — then add safe provenance support",
            completedAtMs = 1234L,
        )
        val decoded = WorkspaceRecentGitHubActionReceipt.decode(
            WorkspaceRecentGitHubActionReceipt.encode(receipt)
        )

        assertEquals(receipt, decoded)
        val prompt = WorkspaceRecentGitHubActionReceipt.instructions(requireNotNull(decoded))
        assertTrue(prompt.contains("Exact initiating USER task"))
        assertTrue(prompt.contains(sha))
        assertTrue(prompt.contains("#3342 completed/success"))
    }

    @Test fun possibleSecretTaskIsNotPersistedIntoProvenance() {
        val receipt = WorkspaceRecentGitHubActionReceipt.fromCompletion(
            completion(),
            "api_key=sk-12345678901234567890 add this to config",
            completedAtMs = 1234L,
        )

        assertNull(receipt.userTask)
        val encoded = WorkspaceRecentGitHubActionReceipt.encode(receipt)
        assertFalse(encoded.contains("sk-12345678901234567890"))
    }

    @Test fun promptPublishesStableReferenceCandidateWithoutClaimingRepoWideLatest() {
        val receipt = WorkspaceRecentGitHubActionReceipt.fromCompletion(
            completion(),
            "Add reference grounding",
            completedAtMs = 1234L,
        )

        val prompt = WorkspaceRecentGitHubActionReceipt.instructions(receipt)
        assertTrue(prompt.contains("Reference candidate id: RECENT_VERIFIED_GITHUB_ACTION"))
        assertTrue(prompt.contains("most recent LYRA connected-repository write"))
        assertTrue(prompt.contains("not proof of the repository's globally newest external action"))
    }
}
