package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubOAuthSessionTest {
    private fun pending(): WorkspaceGitHubOAuthSession.Pending =
        WorkspaceGitHubOAuthSession.createForTest(
            stateBytes = ByteArray(32) { it.toByte() },
            verifierBytes = ByteArray(64) { (it + 7).toByte() },
            nowMs = 1_000L,
        )

    @Test fun connectUrlUsesHttpsStateAndPkceChallengeOnly() {
        val p = pending()
        val url = WorkspaceGitHubOAuthSession.connectUrl(
            "https://lyra-github.example.workers.dev",
            p,
        )
        assertTrue(url.startsWith("https://lyra-github.example.workers.dev/github/authorize?"))
        assertTrue(url.contains("state=" + p.state))
        assertTrue(url.contains("code_challenge=" + p.challenge))
        assertTrue(!url.contains(p.verifier))
    }

    @Test fun restoredPendingRequiresMatchingPkcePair() {
        val p = pending()
        assertEquals(
            p,
            WorkspaceGitHubOAuthSession.restore(
                p.state,
                p.verifier,
                p.challenge,
                p.createdAtMs,
            ),
        )
        assertTrue(runCatching {
            WorkspaceGitHubOAuthSession.restore(
                p.state,
                p.verifier,
                "A".repeat(43),
                p.createdAtMs,
            )
        }.isFailure)
    }

    @Test fun callbackRequiresExactStateAndReturnsCode() {
        val p = pending()
        val result = WorkspaceGitHubOAuthSession.parseCallback(
            "lyra://github/callback?code=temporary-code-123456&state=" + p.state,
            p,
            nowMs = 2_000L,
        )
        assertEquals(
            "temporary-code-123456",
            (result as WorkspaceGitHubOAuthSession.Callback.Success).code,
        )
    }

    @Test fun mismatchedStateAndExpiredCallbackFailClosed() {
        val p = pending()
        assertTrue(runCatching {
            WorkspaceGitHubOAuthSession.parseCallback(
                "lyra://github/callback?code=temporary-code-123456&state=wrong-state-value-1234567890",
                p,
                nowMs = 2_000L,
            )
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceGitHubOAuthSession.parseCallback(
                "lyra://github/callback?code=temporary-code-123456&state=" + p.state,
                p,
                nowMs = 1_000L + 16 * 60 * 1000L,
            )
        }.isFailure)
    }
}
