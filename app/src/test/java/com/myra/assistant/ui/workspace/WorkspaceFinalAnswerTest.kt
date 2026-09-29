package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFinalAnswerTest {
    private fun success(
        warning: String? = null,
        pullRequest: WorkspaceGitHubConnector.PullRequestReceipt? =
            WorkspaceGitHubConnector.PullRequestReceipt(
                action = "updated",
                number = 7,
                url = "https://example.invalid/pr/7",
                draft = true,
                head = "agent/myra-phase-1",
                base = "main",
            ),
    ) = WorkspaceGitHubSelfEditFlow.Completion(
        commit = WorkspaceGitHubConnector.CommitReceipt(
            repository = "spy626/myra-android",
            branch = "agent/myra-phase-1",
            previousHead = "a".repeat(40),
            commitSha = "b".repeat(40),
            files = listOf(
                "app/src/main/java/com/myra/assistant/ui/workspace/A.kt",
                "app/src/test/java/com/myra/assistant/ui/workspace/ATest.kt",
            ),
        ),
        workflow = WorkspaceGitHubConnector.WorkflowRun(
            id = 99L,
            runNumber = 3272L,
            name = "Build Android APK",
            headSha = "b".repeat(40),
            status = "completed",
            conclusion = "success",
            url = "https://example.invalid/run/99",
        ),
        pullRequest = pullRequest,
        warning = warning,
    )

    @Test fun githubFinalAnswerIsHumanReadableAndEvidenceGrounded() {
        val answer = WorkspaceFinalAnswer.githubSuccess(success())

        assertTrue(answer.startsWith("✅ GitHub task complete"))
        assertTrue(answer.contains("What changed:"))
        assertTrue(answer.contains("2 files"))
        assertTrue(answer.contains("CI #3272 GREEN"))
        assertTrue(answer.contains("commit `" + "b".repeat(12) + "`"))
        assertTrue(answer.contains("write stayed on `agent/myra-phase-1`"))
        assertTrue(answer.contains("Draft PR #7 is updated"))
        assertTrue(answer.contains("Next: test the changed behavior on your phone"))
        assertTrue(answer.contains("not physical phone behavior"))

        assertFalse(answer.contains("provider 1/5"))
        assertFalse(answer.contains("review 1/3"))
        assertFalse(answer.contains("xKiro"))
        assertFalse(answer.contains("Groq"))
        assertFalse(answer.contains(" on agent/myra-phase-1 · commit "))
        assertFalse(answer.contains("phone pass", ignoreCase = true))
    }

    @Test fun missingDraftPrIsAQualifiedNoteNotAFakeFailure() {
        val answer = WorkspaceFinalAnswer.githubSuccess(
            success(
                warning = "CI passed, but the draft PR update was not confirmed.",
                pullRequest = null,
            )
        )

        assertTrue(answer.contains("CI #3272 GREEN"))
        assertTrue(answer.contains("Draft PR update was not confirmed"))
        assertTrue(answer.contains("Note: CI passed, but the draft PR update was not confirmed."))
        assertFalse(answer.contains("task failed", ignoreCase = true))
    }

    @Test fun finalAnswerRefusesMismatchedOrUnfinishedCi() {
        val base = success()
        assertTrue(runCatching {
            WorkspaceFinalAnswer.githubSuccess(
                base.copy(workflow = base.workflow.copy(headSha = "c".repeat(40)))
            )
        }.isFailure)

        assertTrue(runCatching {
            WorkspaceFinalAnswer.githubSuccess(
                base.copy(workflow = base.workflow.copy(
                    status = "in_progress",
                    conclusion = null,
                ))
            )
        }.isFailure)
    }
}