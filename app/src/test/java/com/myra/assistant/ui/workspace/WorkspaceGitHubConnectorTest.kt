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

    @Test fun deviceCodeRequestUsesOfficialGithubQueryParameter() {
        val request = WorkspaceGitHubConnector.deviceCodeRequest()

        assertEquals("POST", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("github.com", request.url.host)
        assertEquals("/login/device/code", request.url.encodedPath)
        assertEquals(
            WorkspaceGitHubConnector.GITHUB_APP_CLIENT_ID,
            request.url.queryParameter("client_id"),
        )
    }

    @Test fun deviceTokenRequestUsesOfficialGithubQueryParameters() {
        val deviceCode = "0123456789abcdef0123456789abcdef01234567"
        val request = WorkspaceGitHubConnector.deviceTokenRequest(deviceCode)

        assertEquals("POST", request.method)
        assertEquals("github.com", request.url.host)
        assertEquals("/login/oauth/access_token", request.url.encodedPath)
        assertEquals(
            WorkspaceGitHubConnector.GITHUB_APP_CLIENT_ID,
            request.url.queryParameter("client_id"),
        )
        assertEquals(deviceCode, request.url.queryParameter("device_code"))
        assertEquals(
            "urn:ietf:params:oauth:grant-type:device_code",
            request.url.queryParameter("grant_type"),
        )
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
