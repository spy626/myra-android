package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubSelfEditBatchTest {
    @Test fun candidateSelectionIsRelatedAndBoundedToThreeFiles() {
        val map = WorkspaceAgentReachGitHub.RepositoryPathMap(
            commitSha = "a".repeat(40),
            entries = listOf(
                WorkspaceAgentReachGitHub.PathEntry("src/CheckoutScreen.kt", WorkspaceAgentReachGitHub.PathEntryKind.FILE, "b".repeat(40), 100),
                WorkspaceAgentReachGitHub.PathEntry("src/CheckoutState.kt", WorkspaceAgentReachGitHub.PathEntryKind.FILE, "c".repeat(40), 100),
                WorkspaceAgentReachGitHub.PathEntry("src/CheckoutTest.kt", WorkspaceAgentReachGitHub.PathEntryKind.FILE, "d".repeat(40), 100),
                WorkspaceAgentReachGitHub.PathEntry("src/CheckoutExtra.kt", WorkspaceAgentReachGitHub.PathEntryKind.FILE, "e".repeat(40), 100),
                WorkspaceAgentReachGitHub.PathEntry("src/Unrelated.kt", WorkspaceAgentReachGitHub.PathEntryKind.FILE, "f".repeat(40), 100),
            ),
        )
        val selected = WorkspaceGitHubSelfEditBatch.selectCandidates("GitHub checkout files fix karo", map)
        assertEquals(3, selected.size)
        assertTrue(selected.all { it.path.contains("Checkout") })
    }

    @Test fun batchPatchCanCoordinateTwoSelectedFiles() {
        val originals = linkedMapOf(
            "src/A.kt" to "fun a() = 1\n",
            "src/B.kt" to "fun b() = 2\n",
        )
        val raw = """{"schemaVersion":2,"operation":"replace_exact_once_batch","edits":[{"path":"src/A.kt","oldText":"= 1","newText":"= 10"},{"path":"src/B.kt","oldText":"= 2","newText":"= 20"}],"rationale":"coordinated fix"}"""
        val prepared = WorkspaceGitHubSelfEditBatch.prepare(raw, originals)
        assertEquals(2, prepared.files.size)
        assertEquals("fun a() = 10\n", prepared.files.first { it.path == "src/A.kt" }.content)
        assertEquals("fun b() = 20\n", prepared.files.first { it.path == "src/B.kt" }.content)
    }

    @Test fun batchPatchRejectsUnselectedAndDuplicatePaths() {
        val originals = linkedMapOf("src/A.kt" to "fun a() = 1\n")
        val unselected = """{"schemaVersion":2,"operation":"replace_exact_once_batch","edits":[{"path":"src/B.kt","oldText":"x","newText":"y"}],"rationale":""}"""
        assertTrue(runCatching { WorkspaceGitHubSelfEditBatch.prepare(unselected, originals) }.isFailure)

        val two = linkedMapOf("src/A.kt" to "fun a() = 1\n", "src/B.kt" to "fun b() = 2\n")
        val duplicate = """{"schemaVersion":2,"operation":"replace_exact_once_batch","edits":[{"path":"src/A.kt","oldText":"= 1","newText":"= 3"},{"path":"src/A.kt","oldText":"fun a","newText":"fun aa"}],"rationale":""}"""
        assertTrue(runCatching { WorkspaceGitHubSelfEditBatch.prepare(duplicate, two) }.isFailure)
    }

    @Test fun repairPromptCarriesExactCiEvidence() {
        val prompt = WorkspaceGitHubSelfEditBatch.prompt(
            "GitHub checkout code fix karo",
            linkedMapOf("src/A.kt" to "fun a() = 1\n", "src/B.kt" to "fun b() = 2\n"),
            "CI #42 failed; failed_steps=Unit tests",
        )
        assertTrue(prompt.contains("SAME LYRA GitHub task"))
        assertTrue(prompt.contains("CI #42 failed"))
        assertTrue(prompt.contains("src/A.kt"))
        assertTrue(prompt.contains("src/B.kt"))
    }
    @Test fun reviewerRevisionPromptCarriesFeedbackWithoutClaimingVerification() {
        val prompt = WorkspaceGitHubSelfEditBatch.reviewRevisionPrompt(
            message = "GitHub checkout code fix karo",
            sources = linkedMapOf(
                "src/A.kt" to "fun a() = 1\n",
                "src/B.kt" to "fun b() = 2\n",
            ),
            reviewSummary = "Handle negative input before committing.",
            reviewRisks = listOf("negative limit can crash"),
        )
        assertTrue(prompt.contains("SAME LYRA GitHub task"))
        assertTrue(prompt.contains("Handle negative input"))
        assertTrue(prompt.contains("negative limit can crash"))
        assertTrue(prompt.contains("src/A.kt"))
        assertTrue(prompt.contains("Do not claim build"))
    }

}
