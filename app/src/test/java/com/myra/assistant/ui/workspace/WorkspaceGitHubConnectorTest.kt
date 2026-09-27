package com.myra.assistant.ui.workspace

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubConnectorTest {
    private val token = "github_pat_123456789012345678901234567890"

    @Test fun authenticatedRequestsStayOnApiGithubAndKeepTokenOutOfUrl() {
        val user = WorkspaceGitHubConnector.userRequest(token)
        val repo = WorkspaceGitHubConnector.repositoryRequest(token, "spy626/myra-android")
        val branch = WorkspaceGitHubConnector.branchRequest(
            token,
            "spy626/myra-android",
            "agent/myra-phase-1",
        )

        listOf(user, repo, branch).forEach { request ->
            assertEquals("https", request.url.scheme)
            assertEquals("api.github.com", request.url.host)
            assertTrue(request.url.toString().contains(token).not())
            assertEquals("Bearer $token", request.header("Authorization"))
        }
        assertTrue(branch.url.encodedPath.contains("agent%2Fmyra-phase-1"))
    }

    @Test fun oauthExchangeUsesBrokerPostAndKeepsSecretsOutOfUrl() {
        val code = "temporary-code-123456"
        val state = "state-value-12345678901234567890"
        val verifier = "v".repeat(64)
        val request = WorkspaceGitHubConnector.oauthExchangeRequest(code, state, verifier)

        assertEquals("POST", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("lyra-github-connector.everspy626.workers.dev", request.url.host)
        assertEquals("/github/exchange", request.url.encodedPath)
        assertTrue(!request.url.toString().contains(code))
        assertTrue(!request.url.toString().contains(verifier))

        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = buffer.readUtf8()
        assertTrue(body.contains(code))
        assertTrue(body.contains(state))
        assertTrue(body.contains(verifier))
    }

    @Test fun unsafeTokenAndMainBranchAreRejectedBeforeNetworking() {
        assertTrue(runCatching {
            WorkspaceGitHubConnector.userRequest("short")
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceGitHubConnector.branchRequest(token, "spy626/myra-android", "main")
        }.isFailure)
    }
}
