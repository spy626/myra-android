package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentReachDeliberationEvidenceTest {
    private val sha = "1234567890abcdef1234567890abcdef12345678"
    private val target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")

    private fun evidence(path: String, content: String) =
        WorkspaceAgentReachEvidence.create(
            requested = target,
            final = WorkspaceAgentReachPolicy.parse(
                "https://github.com/a/b/blob/$sha/$path"),
            adapter = "github-public-read",
            content = content,
            fetchedAtMs = 1L,
            revision = sha,
        )

    private fun base(content: String = "# README\nBrowser security notes.") =
        WorkspaceAgentReachGitHubRunner.Completion(
            evidence = evidence("README.md", content),
            repositoryMeta = null,
            repositoryIndex = null,
        )

    private fun relevant(vararg files: Pair<String,String>) =
        WorkspaceAgentReachGitHubRelevantRunner.Completion(
            commitSha = sha,
            pathMap = WorkspaceAgentReachGitHub.RepositoryPathMap(
                sha,
                files.map {
                    WorkspaceAgentReachGitHub.PathEntry(
                        path = it.first,
                        kind = WorkspaceAgentReachGitHub.PathEntryKind.FILE,
                        sha = "a".repeat(40),
                        size = it.second.length,
                    )
                }
            ),
            files = files.map {
                WorkspaceAgentReachGitHubRelevantRunner.FileEvidence(
                    candidate = WorkspaceAgentReachGitHubRelevance.Candidate(
                        path = it.first,
                        score = 50,
                        reason = "query match",
                        size = it.second.length,
                    ),
                    evidence = evidence(it.first, it.second),
                )
            }
        )

    @Test fun packIsPinnedBoundedAndCarriesProvenance() {
        val pack = WorkspaceAgentReachDeliberationEvidence.build(
            base(),
            relevant(
                "src/security.kt" to "fun validateUrl() = true",
                "SKILL.md" to "Use bounded repository evidence."
            )
        )
        assertEquals(sha, pack.revision)
        assertEquals(64, pack.sha256.length)
        assertTrue(pack.text.contains("EXTERNAL GITHUB EVIDENCE"))
        assertTrue(pack.text.contains("Pinned revision: $sha"))
        assertTrue(pack.text.contains("src/security.kt"))
        assertTrue(pack.text.contains("SKILL.md"))
        assertTrue(pack.text.length <= 3_600)
    }

    @Test fun secretLikeFilesAreExcludedBeforeProjection() {
        val pack = WorkspaceAgentReachDeliberationEvidence.build(
            base(),
            relevant(
                "src/security.kt" to "fun safe() = true",
                "docs/secret.md" to "api_key = sk-abcdefghijklmnop"
            )
        )
        assertTrue("src/security.kt" in pack.includedFiles)
        assertTrue("docs/secret.md" in pack.excludedSensitiveFiles)
        assertFalse(pack.text.contains("sk-abcdefghijklmnop"))
    }

    @Test fun revisionMismatchFailsClosed() {
        val bad = relevant("src/security.kt" to "safe").copy(commitSha = "f".repeat(40))
        assertTrue(runCatching {
            WorkspaceAgentReachDeliberationEvidence.build(base(), bad)
        }.isFailure)
    }

    @Test fun allSensitiveEvidenceCannotCreateProviderPack() {
        val sensitiveBase = base("token = sk-abcdefghijklmnop")
        assertTrue(runCatching {
            WorkspaceAgentReachDeliberationEvidence.build(
                sensitiveBase,
                relevant("docs/secret.md" to "api_key = sk-abcdefghijklmnop"))
        }.isFailure)
    }
}
