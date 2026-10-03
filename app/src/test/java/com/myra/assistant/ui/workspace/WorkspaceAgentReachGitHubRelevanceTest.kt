package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentReachGitHubRelevanceTest {
    private fun mapOf(vararg entries: WorkspaceAgentReachGitHub.PathEntry) =
        WorkspaceAgentReachGitHub.RepositoryPathMap(
            commitSha = "1234567890abcdef1234567890abcdef12345678",
            entries = entries.toList()
        )

    private fun file(path: String, size: Int = 1_000) =
        WorkspaceAgentReachGitHub.PathEntry(
            path = path,
            kind = WorkspaceAgentReachGitHub.PathEntryKind.FILE,
            sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            size = size
        )

    @Test fun userTermsRankMatchingFilesWithoutReadingWholeRepo() {
        val selected = WorkspaceAgentReachGitHubRelevance.select(
            "check this repo and find browser security ideas",
            mapOf(
                file("src/browser/security_watchdog.py"),
                file("src/payments/billing.py"),
                file("docs/browser.md"),
                file("README.md")
            )
        )
        assertTrue(selected.isNotEmpty())
        assertEquals("src/browser/security_watchdog.py", selected.first().path)
        assertTrue(selected.size <= WorkspaceAgentReachGitHubRelevance.MAX_SELECTED_FILES)
    }

    @Test fun highSignalSkillAndArchitectureFilesCanSurfaceWithoutExactQueryToken() {
        val selected = WorkspaceAgentReachGitHubRelevance.select(
            "check this repo",
            mapOf(
                file("SKILL.md"),
                file("docs/ARCHITECTURE.md"),
                file("src/random.py")
            )
        )
        assertEquals("SKILL.md", selected.first().path)
        assertTrue(selected.any { it.path == "docs/ARCHITECTURE.md" })
    }

    @Test fun readmeBuildOutputsSecretsAndLargeFilesAreExcluded() {
        val selected = WorkspaceAgentReachGitHubRelevance.select(
            "check agent config security",
            mapOf(
                file("README.md"),
                file(".env"),
                file("secrets/api_key.txt"),
                file("build/agent.kt"),
                file("src/agent.kt", 60_000),
                file("src/agent.md", 4_000)
            )
        )
        assertEquals(listOf("src/agent.md"), selected.map { it.path })
    }

    @Test fun rankingIsDeterministicAndBounded() {
        val map = mapOf(
            file("docs/security.md"),
            file("docs/browser.md"),
            file("src/browser.kt"),
            file("src/security.kt"),
            file("AGENTS.md"),
            file("SKILL.md")
        )
        val first = WorkspaceAgentReachGitHubRelevance.select("browser security", map)
        val second = WorkspaceAgentReachGitHubRelevance.select("browser security", map)
        assertEquals(first, second)
        assertTrue(first.size <= 4)
    }
}
