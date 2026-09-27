package com.myra.assistant.ui.workspace

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubConnectorTest {
    private val token = "ghs_1234567890123456789012345678901234567890"
    private val pairing = "a".repeat(64)

    @Test fun authenticatedRequestsStayOnApiGithubAndKeepTokenOutOfUrl() {
        val repo = WorkspaceGitHubConnector.repositoryRequest(token, "spy626/myra-android")
        val branch = WorkspaceGitHubConnector.branchRequest(
            token,
            "spy626/myra-android",
            "agent/myra-phase-1",
        )

        listOf(repo, branch).forEach { request ->
            assertEquals("https", request.url.scheme)
            assertEquals("api.github.com", request.url.host)
            assertTrue(request.url.toString().contains(token).not())
            assertEquals("Bearer $token", request.header("Authorization"))
        }
        assertTrue(branch.url.encodedPath.contains("agent%2Fmyra-phase-1"))
    }

    @Test fun installationTokenRequestUsesBrokerAndKeepsPairingSecretOutOfUrl() {
        val request = WorkspaceGitHubConnector.installationTokenRequest(pairing)

        assertEquals("POST", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("lyra-github-connector.everspy626.workers.dev", request.url.host)
        assertEquals("/github/token", request.url.encodedPath)
        assertTrue(!request.url.toString().contains(pairing))

        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = buffer.readUtf8()
        assertTrue(body.contains(pairing))
    }

    @Test fun generatedPairingKeyIsHighEntropyShapeAndValidated() {
        val generated = WorkspaceGitHubConnector.newPairingSecret()
        assertEquals(64, generated.length)
        assertEquals(generated, WorkspaceGitHubConnector.requirePairingSecret(generated))
        assertTrue(generated.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun unsafeTokenPairingAndMainBranchAreRejectedBeforeNetworking() {
        assertTrue(runCatching {
            WorkspaceGitHubConnector.repositoryRequest("short", "spy626/myra-android")
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceGitHubConnector.installationTokenRequest("not-a-pairing-key")
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceGitHubConnector.branchRequest(token, "spy626/myra-android", "main")
        }.isFailure)
    }
}
