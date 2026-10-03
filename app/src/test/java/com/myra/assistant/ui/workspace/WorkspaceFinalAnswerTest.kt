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

    @Test fun githubFinalAnswerFallbackIsConciseAndEvidenceGrounded() {
        val answer = WorkspaceFinalAnswer.githubSuccess(success())

        assertTrue(answer.startsWith("**Done ✅** The requested change across 2 files is complete."))
        assertTrue(answer.contains("CI #3272 is GREEN"))
        assertTrue(answer.contains("configured build/tests passed for this pushed commit"))
        assertTrue(answer.contains("Main/master was not written or merged"))
        assertTrue(answer.contains("Phone behavior still needs a physical phone test"))

        assertFalse(answer.contains("What changed:"))
        assertFalse(answer.contains("commit `"))
        assertFalse(answer.contains("Draft PR #7"))
        assertFalse(answer.contains("agent/myra-phase-1"))
        assertFalse(answer.contains("provider 1/5"))
        assertFalse(answer.contains("xKiro"))
        assertFalse(answer.contains("Groq"))
        assertFalse(answer.contains("phone pass", ignoreCase = true))
    }

    @Test fun githubFallbackMatchesHinglishUserToneWithoutRawTechnicalDump() {
        val answer = WorkspaceFinalAnswer.githubSuccess(
            result = success(),
            userTask = "Bro GitHub repo me A.kt me safe change karo aur exact CI GREEN tak verify karo.",
        )

        assertTrue(answer.startsWith("**Done bro ✅**"))
        assertTrue(answer.contains("2 files me complete ho gaya"))
        assertTrue(answer.contains("CI #3272 GREEN hai"))
        assertTrue(answer.contains("configured build/tests pass hue"))
        assertTrue(answer.contains("Main/master ko touch ya merge nahi kiya"))
        assertTrue(answer.contains("Phone behavior ka final check physical phone test se hi hoga"))
        assertFalse(answer.contains("agent/myra-phase-1"))
        assertFalse(answer.contains("Draft PR #"))
        assertFalse(answer.contains("sab kuch verified", ignoreCase = true))
        assertFalse(answer.contains("commit `" + "b".repeat(12)))
    }

    @Test fun sensitiveUserTaskIsNeverEchoedIntoFallback() {
        val answer = WorkspaceFinalAnswer.githubSuccess(
            result = success(),
            userTask = "Bro GitHub repo me A.kt update karo api_key=sk-super-secret-123456",
        )

        assertFalse(answer.contains("super-secret"))
        assertFalse(answer.contains("api_key"))
        assertTrue(answer.contains("CI #3272"))
    }

    @Test fun missingDraftPrIsAQualifiedNoteNotAFakeFailure() {
        val answer = WorkspaceFinalAnswer.githubSuccess(
            success(
                warning = "CI passed, but the draft PR update was not confirmed.",
                pullRequest = null,
            )
        )

        assertTrue(answer.contains("CI #3272 is GREEN"))
        assertTrue(answer.contains("Note: CI passed, but the draft PR update was not confirmed."))
        assertFalse(answer.contains("Draft PR update was not confirmed.") &&
            !answer.contains("Note:"))
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

        assertTrue(runCatching {
            WorkspaceFinalAnswer.githubSuccess(
                base.copy(
                    pullRequest = base.pullRequest?.copy(head = "wrong-branch"),
                )
            )
        }.isFailure)
    }
}