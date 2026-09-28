package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubWritePolicyTest {
    private val head = "1234567890abcdef1234567890abcdef12345678"

    @Test fun boundedTextCommitIsCanonicalized() {
        val plan = WorkspaceGitHubWritePolicy.commitPlan(
            expectedHead = head.uppercase(),
            message = " feat: safe write ",
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange(
                    "app/src/main/java/Test.kt",
                    "package test\n",
                )
            ),
        )
        assertEquals(head, plan.expectedHead)
        assertEquals("feat: safe write", plan.message)
        assertEquals("app/src/main/java/Test.kt", plan.files.single().path)
    }

    @Test fun workflowsCredentialsSecretsAndDuplicatePathsAreBlocked() {
        val badPaths = listOf(
            ".github/workflows/android.yml",
            ".env",
            "certs/app.pem",
            ".git/config",
        )
        badPaths.forEach { path ->
            assertTrue(runCatching { WorkspaceGitHubWritePolicy.requirePath(path) }.isFailure)
        }
        assertTrue(runCatching {
            WorkspaceGitHubWritePolicy.requireContent(
                "-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----"
            )
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceGitHubWritePolicy.commitPlan(
                head,
                "test",
                listOf(
                    WorkspaceGitHubWritePolicy.FileChange("a.txt", "1"),
                    WorkspaceGitHubWritePolicy.FileChange("A.txt", "2"),
                ),
            )
        }.isFailure)
    }

    @Test fun pullRequestPlanIsBounded() {
        val plan = WorkspaceGitHubWritePolicy.pullRequestPlan(
            " C2 safe self-edit ",
            "Draft only",
        )
        assertEquals("C2 safe self-edit", plan.title)
        assertEquals("Draft only", plan.body)
    }
}
