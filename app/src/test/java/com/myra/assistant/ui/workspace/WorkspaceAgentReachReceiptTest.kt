package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentReachReceiptTest {
    @Test fun receiptContainsProvenanceButNotExternalBody() {
        val target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val evidence = WorkspaceAgentReachEvidence.create(
            requested = target,
            final = WorkspaceAgentReachPolicy.parse(
                "https://github.com/a/b/blob/1234567890abcdef1234567890abcdef12345678/README.md"),
            adapter = "github-public-read",
            content = "UNTRUSTED README BODY SHOULD NOT APPEAR IN RECEIPT",
            fetchedAtMs = 1L,
            revision = "1234567890abcdef1234567890abcdef12345678",
        )
        val index = WorkspaceAgentReachGitHub.RepositoryIndex(
            commitSha = "1234567890abcdef1234567890abcdef12345678",
            entries = listOf(
                WorkspaceAgentReachGitHub.RootEntry(
                    "src", "src", WorkspaceAgentReachGitHub.RootEntryKind.DIRECTORY,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", null),
                WorkspaceAgentReachGitHub.RootEntry(
                    "README.md", "README.md", WorkspaceAgentReachGitHub.RootEntryKind.FILE,
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 120),
            )
        )
        val receipt = WorkspaceAgentReachReceipt.github(evidence, index)
        assertTrue(receipt.contains("Pinned revision: 1234567890ab"))
        assertTrue(receipt.contains(evidence.provenance.contentSha256))
        assertFalse(receipt.contains("UNTRUSTED README BODY"))
        assertTrue(receipt.contains("Pinned root index: 2 entries"))
        assertTrue(receipt.contains("Root items: README.md, src/"))
        assertTrue(receipt.contains("AI provider"))
    }

    @Test fun relevantFileReceiptContainsReasonsAndHashesButNeverBodies() {
        val target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val evidence = WorkspaceAgentReachEvidence.create(
            requested = target,
            final = WorkspaceAgentReachPolicy.parse(
                "https://github.com/a/b/blob/1234567890abcdef1234567890abcdef12345678/README.md"),
            adapter = "github-public-read",
            content = "README BODY",
            fetchedAtMs = 1L,
            revision = "1234567890abcdef1234567890abcdef12345678",
        )
        val receipt = WorkspaceAgentReachReceipt.github(
            evidence = evidence,
            relevantFiles = listOf(
                WorkspaceAgentReachReceipt.RelevantFile(
                    path = "src/security.kt",
                    reason = "filename matches 'security'",
                    contentSha256 = "a".repeat(64),
                )
            ),
            relevantPathCount = 42,
        )
        assertTrue(receipt.contains("Relevant-file scan: 1 selected from 42 indexed paths"))
        assertTrue(receipt.contains("src/security.kt"))
        assertTrue(receipt.contains("filename matches 'security'"))
        assertTrue(receipt.contains("a".repeat(64)))
        assertFalse(receipt.contains("README BODY"))
        assertFalse(receipt.contains("RELEVANT RAW BODY"))
    }
}
