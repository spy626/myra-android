package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubSelfEditTest {
    @Test fun requiresCurrentTurnMutationAndConnectedRepoScope() {
        assertTrue(WorkspaceGitHubSelfEdit.isExplicitRequest(
            "Bro LYRA ke GitHub code me GitHub screen ka button remove karo"))
        assertTrue(WorkspaceGitHubSelfEdit.isExplicitRequest(
            "MYRA source code me connector status fix karo"))
        assertFalse(WorkspaceGitHubSelfEdit.isExplicitRequest(
            "GitHub repo check karo aur explain karo"))
        assertFalse(WorkspaceGitHubSelfEdit.isExplicitRequest(
            "LYRA code baad me fix karna"))
        assertFalse(WorkspaceGitHubSelfEdit.isExplicitRequest(
            "Android app banao"))
    }

    @Test fun structuredPatchMustTargetOneExactUniqueSpan() {
        val raw = """{"schemaVersion":1,"operation":"replace_exact_once","path":"src/Test.kt","oldText":"old value","newText":"new value","rationale":"requested fix"}"""
        val prepared = WorkspaceGitHubSelfEdit.prepare(
            raw,
            "src/Test.kt",
            "before\nold value\nafter\n",
        )
        assertEquals("src/Test.kt", prepared.path)
        assertEquals("before\nnew value\nafter\n", prepared.content)
        assertTrue(runCatching {
            WorkspaceGitHubSelfEdit.prepare(raw, "src/Test.kt", "old value and old value")
        }.isFailure)
    }

    @Test fun candidateSelectionUsesFilenameOrPathMatch() {
        val map = WorkspaceAgentReachGitHub.RepositoryPathMap(
            commitSha = "a".repeat(40),
            entries = listOf(
                WorkspaceAgentReachGitHub.PathEntry(
                    "ARCHITECTURE.md",
                    WorkspaceAgentReachGitHub.PathEntryKind.FILE,
                    "b".repeat(40),
                    100,
                ),
                WorkspaceAgentReachGitHub.PathEntry(
                    "app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceGitHubConnectorActivity.kt",
                    WorkspaceAgentReachGitHub.PathEntryKind.FILE,
                    "c".repeat(40),
                    100,
                ),
            ),
        )
        val selected = WorkspaceGitHubSelfEdit.selectCandidate(
            "GitHub connector screen fix karo",
            map,
        )
        assertTrue(selected.path.contains("WorkspaceGitHubConnectorActivity.kt"))
    }
    @Test fun repairPromptKeepsSameTaskAndBoundedCiEvidence() {
        val prompt = WorkspaceGitHubSelfEdit.repairPrompt(
            "GitHub source me button state fix karo",
            "src/Test.kt",
            "fun state() = \"wrong\"",
            "GitHub Actions Build Android APK #99 failed; job=build; failed_steps=Unit tests",
        )
        assertTrue(prompt.contains("SAME LYRA GitHub task"))
        assertTrue(prompt.contains("Build Android APK #99"))
        assertTrue(prompt.contains("CURRENT SOURCE EXCERPT"))
        assertTrue(prompt.contains("src/Test.kt"))
    }

}
