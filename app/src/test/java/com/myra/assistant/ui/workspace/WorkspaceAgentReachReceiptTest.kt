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
        val receipt = WorkspaceAgentReachReceipt.github(evidence)
        assertTrue(receipt.contains("Pinned revision: 1234567890ab"))
        assertTrue(receipt.contains(evidence.provenance.contentSha256))
        assertFalse(receipt.contains("UNTRUSTED README BODY"))
        assertTrue(receipt.contains("AI provider"))
    }
}
