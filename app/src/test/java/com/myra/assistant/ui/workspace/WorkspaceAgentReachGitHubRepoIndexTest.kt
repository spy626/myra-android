package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentReachGitHubRepoIndexTest {
    private val commitSha = "1234567890abcdef1234567890abcdef12345678"
    private val objectSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    private fun entry(
        path: String,
        kind: WorkspaceAgentReachGitHub.PathEntryKind =
            WorkspaceAgentReachGitHub.PathEntryKind.FILE,
        size: Int? = 100,
    ) = WorkspaceAgentReachGitHub.PathEntry(
        path = path,
        kind = kind,
        sha = objectSha,
        size = size,
    )

    @Test fun categorizesPinnedPathsAndExcludesGeneratedTrees() {
        val pathMap = WorkspaceAgentReachGitHub.RepositoryPathMap(
            commitSha = commitSha,
            entries = listOf(
                entry("README.md"),
                entry("docs/architecture.md"),
                entry("src/main/App.kt"),
                entry("package.json"),
                entry("skills/browser/SKILL.md"),
                entry("src/test/AppTest.kt"),
                entry(".github/workflows/ci.yml"),
                entry("node_modules/pkg/index.js"),
                entry("assets/logo.png"),
            ),
        )

        val index = WorkspaceAgentReachGitHubRepoIndex.build(pathMap)

        assertEquals(commitSha, index.commitSha)
        assertEquals(9, index.observedEntries)
        assertEquals(1, index.excludedGeneratedEntries)
        assertEquals(8, index.entries.size)
        assertFalse(index.entries.any { it.path.startsWith("node_modules/") })
        assertEquals(
            WorkspaceAgentReachGitHubRepoIndex.Category.README_DOCS,
            index.entries.first { it.path == "README.md" }.category)
        assertEquals(
            WorkspaceAgentReachGitHubRepoIndex.Category.SOURCE,
            index.entries.first { it.path == "src/main/App.kt" }.category)
        assertEquals(
            WorkspaceAgentReachGitHubRepoIndex.Category.MANIFEST_BUILD,
            index.entries.first { it.path == "package.json" }.category)
        assertEquals(
            WorkspaceAgentReachGitHubRepoIndex.Category.SKILLS_PLUGINS,
            index.entries.first { it.path == "skills/browser/SKILL.md" }.category)
        assertEquals(
            WorkspaceAgentReachGitHubRepoIndex.Category.TESTS,
            index.entries.first { it.path == "src/test/AppTest.kt" }.category)
        assertEquals(
            WorkspaceAgentReachGitHubRepoIndex.Category.WORKFLOWS_CONFIG,
            index.entries.first { it.path == ".github/workflows/ci.yml" }.category)
        assertEquals(
            WorkspaceAgentReachGitHubRepoIndex.Category.OTHER,
            index.entries.first { it.path == "assets/logo.png" }.category)
        assertEquals(
            objectSha,
            index.entries.first { it.path == "src/main/App.kt" }.objectSha)
    }

    @Test fun rejectsOversizedEntryCountInsteadOfTruncatingSilently() {
        val pathMap = WorkspaceAgentReachGitHub.RepositoryPathMap(
            commitSha = commitSha,
            entries = (0..WorkspaceAgentReachGitHubRepoIndex.MAX_INDEX_ENTRIES)
                .map { entry("src/File$it.kt") },
        )

        assertTrue(runCatching {
            WorkspaceAgentReachGitHubRepoIndex.build(pathMap)
        }.isFailure)
    }

    @Test fun rejectsOversizedPathBudgetInsteadOfTruncatingSilently() {
        val left = "a".repeat(210)
        val right = "b".repeat(205)
        val pathMap = WorkspaceAgentReachGitHub.RepositoryPathMap(
            commitSha = commitSha,
            entries = (0 until 230).map { i ->
                entry("src/" + left + "/" + right + i + ".kt")
            },
        )

        val failure = runCatching {
            WorkspaceAgentReachGitHubRepoIndex.build(pathMap)
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure?.message.orEmpty().contains("path budget"))
    }
}
