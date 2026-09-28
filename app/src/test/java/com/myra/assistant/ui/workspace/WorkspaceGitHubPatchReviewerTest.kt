package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubPatchReviewerTest {
    private val plan = WorkspaceGitHubCodingPlan.create(
        goal = "GitHub checkout state fix karo",
        repository = "spy626/myra-android",
        branch = "agent/myra-phase-1",
        selectedPaths = listOf("src/A.kt", "src/B.kt"),
    )

    @Test fun reviewPromptIsReadOnlyAndBoundedToLockedScope() {
        val originals = linkedMapOf(
            "src/A.kt" to "fun a() = 1\n",
            "src/B.kt" to "fun b() = 2\n",
        )
        val prepared = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange("src/A.kt", "fun a() = 10\n")
            ),
            rationale = "fix",
        )
        val prompt = WorkspaceGitHubPatchReviewer.prompt(plan.goal, plan, originals, prepared)
        assertTrue(prompt.contains("READ-ONLY QA reviewer"))
        assertTrue(prompt.contains("ACCEPT"))
        assertTrue(prompt.contains("src/A.kt"))
        assertTrue(prompt.contains("fun a() = 1"))
        assertTrue(prompt.contains("fun a() = 10"))
    }

    @Test fun strictReviewContractAcceptsOnlyKnownDecisionsAndKeys() {
        val accepted = WorkspaceGitHubPatchReviewer.read(
            """{"schemaVersion":1,"decision":"ACCEPT","summary":"Looks consistent.","risks":[]}"""
        )
        assertEquals(WorkspaceGitHubPatchReviewer.Decision.ACCEPT, accepted.decision)

        val revise = WorkspaceGitHubPatchReviewer.read(
            """{"schemaVersion":1,"decision":"REVISE","summary":"Missing guard.","risks":["negative input"]}"""
        )
        assertEquals(WorkspaceGitHubPatchReviewer.Decision.REVISE, revise.decision)

        assertTrue(runCatching {
            WorkspaceGitHubPatchReviewer.read(
                """{"schemaVersion":1,"decision":"MAYBE","summary":"x","risks":[]}"""
            )
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceGitHubPatchReviewer.read(
                """{"schemaVersion":1,"decision":"ACCEPT","summary":"x","risks":[],"patch":"no"}"""
            )
        }.isFailure)
    }

    @Test fun reviewerCannotInspectPathOutsideLockedPlan() {
        val originals = linkedMapOf("src/A.kt" to "fun a() = 1\n")
        val prepared = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange("src/Outside.kt", "fun x() = 2\n")
            ),
            rationale = "scope drift",
        )
        assertTrue(runCatching {
            WorkspaceGitHubPatchReviewer.prompt(plan.goal, plan, originals, prepared)
        }.isFailure)
    }
}
