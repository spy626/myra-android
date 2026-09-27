package com.myra.assistant.ui.workspace

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

    @Test fun unsafeTokenAndMainBranchAreRejectedBeforeNetworking() {
        assertTrue(runCatching {
            WorkspaceGitHubConnector.userRequest("short")
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceGitHubConnector.branchRequest(token, "spy626/myra-android", "main")
        }.isFailure)
    }
}
