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
            previousPrepared = WorkspaceGitHubSelfEditBatch.Prepared(
                files = listOf(
                    WorkspaceGitHubWritePolicy.FileChange("src/A.kt", "fun a() = 10\n")
                ),
                rationale = "first proposal",
            ),
            reviewSummary = "Handle negative input before committing.",
            reviewRisks = listOf("negative limit can crash"),
        )
        assertTrue(prompt.contains("SAME LYRA GitHub task"))
        assertTrue(prompt.contains("Handle negative input"))
        assertTrue(prompt.contains("negative limit can crash"))
        assertTrue(prompt.contains("src/A.kt"))
        assertTrue(prompt.contains("PREVIOUS PROPOSED RESULT WINDOW"))
        assertTrue(prompt.contains("fun a() = 10"))
        assertTrue(prompt.contains("Build the final replacement against CURRENT SOURCE"))
        assertTrue(prompt.contains("REQUIRED CORRECTION CONSTRAINTS"))
        assertTrue(prompt.contains("Do not repeat the rejected proposal unchanged"))
        assertTrue(prompt.contains("Do not claim build"))
    }

    @Test fun explicitFilenamePinsExactlyOneFileBeforeRelatedExpansion() {
        val map = WorkspaceAgentReachGitHub.RepositoryPathMap(
            commitSha = "a".repeat(40),
            entries = listOf(
                WorkspaceAgentReachGitHub.PathEntry(
                    "app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceGitHubCodingPlan.kt",
                    WorkspaceAgentReachGitHub.PathEntryKind.FILE, "b".repeat(40), 100
                ),
                WorkspaceAgentReachGitHub.PathEntry(
                    "app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceGitHubSelfEditBatch.kt",
                    WorkspaceAgentReachGitHub.PathEntryKind.FILE, "c".repeat(40), 100
                ),
                WorkspaceAgentReachGitHub.PathEntry(
                    "app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceGitHubCodingPlanTest.kt",
                    WorkspaceAgentReachGitHub.PathEntryKind.FILE, "d".repeat(40), 100
                ),
            ),
        )
        val selected = WorkspaceGitHubSelfEditBatch.selectCandidates(
            "GitHub WorkspaceGitHubCodingPlan.kt file ke top comment me text change karo.",
            map,
        )
        assertEquals(1, selected.size)
        assertEquals(
            "app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceGitHubCodingPlan.kt",
            selected.single().path,
        )
        assertTrue(selected.single().reason.contains("explicit file reference"))
    }

    @Test fun explicitFullPathDisambiguatesDuplicateBasenameAndBareNameRefusesGuess() {
        val map = WorkspaceAgentReachGitHub.RepositoryPathMap(
            commitSha = "a".repeat(40),
            entries = listOf(
                WorkspaceAgentReachGitHub.PathEntry(
                    "src/main/Foo.kt", WorkspaceAgentReachGitHub.PathEntryKind.FILE,
                    "b".repeat(40), 100
                ),
                WorkspaceAgentReachGitHub.PathEntry(
                    "src/test/Foo.kt", WorkspaceAgentReachGitHub.PathEntryKind.FILE,
                    "c".repeat(40), 100
                ),
            ),
        )
        val exact = WorkspaceGitHubSelfEditBatch.selectCandidates(
            "GitHub src/main/Foo.kt me comment fix karo", map
        )
        assertEquals(listOf("src/main/Foo.kt"), exact.map { it.path })

        val ambiguous = runCatching {
            WorkspaceGitHubSelfEditBatch.selectCandidates("GitHub Foo.kt me comment fix karo", map)
        }.exceptionOrNull()
        assertTrue(ambiguous?.message?.contains("ambiguous") == true)
        assertTrue(ambiguous?.message?.contains("full path") == true)
    }

    @Test fun providerEditCountErrorReportsReturnedAndAllowedCounts() {
        val originals = linkedMapOf("src/A.kt" to "fun a() = 1\n")
        val zero = """{"schemaVersion":2,"operation":"replace_exact_once_batch","edits":[],"rationale":"none"}"""
        val zeroError = runCatching {
            WorkspaceGitHubSelfEditBatch.prepare(zero, originals)
        }.exceptionOrNull()
        assertTrue(zeroError?.message?.contains("returned 0 edits; allowed 1..1") == true)

        val two = """{"schemaVersion":2,"operation":"replace_exact_once_batch","edits":[{"path":"src/A.kt","oldText":"= 1","newText":"= 2"},{"path":"src/A.kt","oldText":"fun a","newText":"fun aa"}],"rationale":"too many"}"""
        val twoError = runCatching {
            WorkspaceGitHubSelfEditBatch.prepare(two, originals)
        }.exceptionOrNull()
        assertTrue(twoError?.message?.contains("returned 2 edits; allowed 1..1") == true)
    }

    @Test fun reviewerRevisionPromptRejectsPreviousProposalOutsideSelectedScope() {
        val sources = linkedMapOf("src/A.kt" to "fun a() = 1\n")
        val previous = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange("src/B.kt", "fun b() = 2\n")
            ),
            rationale = "scope drift",
        )
        assertTrue(runCatching {
            WorkspaceGitHubSelfEditBatch.reviewRevisionPrompt(
                message = "GitHub src/A.kt fix karo",
                sources = sources,
                previousPrepared = previous,
                reviewSummary = "Fix the proposal.",
                reviewRisks = emptyList(),
            )
        }.isFailure)
    }

    @Test fun reviewerRevisionMustMateriallyDifferFromRejectedProposal() {
        val previous = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange("src/A.kt", "fun a() = 2\n")
            ),
            rationale = "first",
        )
        val repeated = previous.copy(rationale = "different words only")
        assertTrue(runCatching {
            WorkspaceGitHubSelfEditBatch.requireMaterialRevision(previous, repeated)
        }.isFailure)

        val corrected = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange("src/A.kt", "fun a() = 3\n")
            ),
            rationale = "review correction",
        )
        assertEquals(
            corrected,
            WorkspaceGitHubSelfEditBatch.requireMaterialRevision(previous, corrected),
        )
    }

    @Test fun reviewerRevisionMayNarrowFilesWhenContentActuallyChanges() {
        val previous = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange("src/A.kt", "fun a() = 2\n"),
                WorkspaceGitHubWritePolicy.FileChange("src/B.kt", "fun b() = 3\n"),
            ),
            rationale = "first",
        )
        val corrected = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange("src/A.kt", "fun a() = 4\n")
            ),
            rationale = "review correction",
        )
        assertEquals(
            corrected,
            WorkspaceGitHubSelfEditBatch.requireMaterialRevision(previous, corrected),
        )
    }

}
