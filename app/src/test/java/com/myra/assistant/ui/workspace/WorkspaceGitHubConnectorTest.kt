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

    @Test fun deviceCodeRequestUsesOfficialGithubPost() {
        val request = WorkspaceGitHubConnector.deviceCodeRequest()

        assertEquals("POST", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("github.com", request.url.host)
        assertEquals("/login/device/code", request.url.encodedPath)

        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = buffer.readUtf8()
        assertTrue(body.contains("client_id="))
        assertTrue(body.contains(WorkspaceGitHubConnector.GITHUB_APP_CLIENT_ID))
    }

    @Test fun deviceTokenRequestKeepsDeviceCodeOutOfUrl() {
        val deviceCode = "0123456789abcdef0123456789abcdef01234567"
        val request = WorkspaceGitHubConnector.deviceTokenRequest(deviceCode)

        assertEquals("POST", request.method)
        assertEquals("github.com", request.url.host)
        assertEquals("/login/oauth/access_token", request.url.encodedPath)
        assertTrue(!request.url.toString().contains(deviceCode))

        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = buffer.readUtf8()
        assertTrue(body.contains("device_code=$deviceCode"))
        assertTrue(body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
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
