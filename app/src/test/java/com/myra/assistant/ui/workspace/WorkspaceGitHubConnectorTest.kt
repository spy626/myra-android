package com.myra.assistant.ui.workspace

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubConnectorTest {
    private val token = "ghs_1234567890123456789012345678901234567890"
    private val pairing = "a".repeat(64)
    private val head = "1234567890abcdef1234567890abcdef12345678"

    @Test fun authenticatedReadRequestsStayOnApiGithubAndKeepTokenOutOfUrl() {
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

    @Test fun brokerRequestsKeepPairingSecretOutOfUrls() {
        val write = WorkspaceGitHubConnector.writeAccessRequest(pairing)
        val commit = WorkspaceGitHubConnector.commitRequest(
            pairing,
            WorkspaceGitHubWritePolicy.commitPlan(
                head,
                "feat: test",
                listOf(WorkspaceGitHubWritePolicy.FileChange("docs/test.txt", "hello")),
            ),
        )
        val pr = WorkspaceGitHubConnector.pullRequestRequest(
            pairing,
            WorkspaceGitHubWritePolicy.pullRequestPlan("Test PR", "body"),
        )
        val prSmoke = WorkspaceGitHubConnector.pullRequestSmokeTestRequest(pairing)
        val prEnsure = WorkspaceGitHubConnector.ensureDraftPullRequestRequest(pairing)

        listOf(write, commit, pr, prSmoke, prEnsure).forEach { request ->
            assertEquals("POST", request.method)
            assertEquals("lyra-github-connector.everspy626.workers.dev", request.url.host)
            assertTrue(!request.url.toString().contains(pairing))
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            assertTrue(buffer.readUtf8().contains(pairing))
        }
        assertEquals("/github/write/check", write.url.encodedPath)
        assertEquals("/github/write/commit", commit.url.encodedPath)
        assertEquals("/github/write/pull-request", pr.url.encodedPath)
        assertEquals("/github/write/pull-request", prSmoke.url.encodedPath)
        assertEquals("/github/write/pull-request", prEnsure.url.encodedPath)
        val ensureBody = Buffer().also { prEnsure.body!!.writeTo(it) }.readUtf8()
        assertTrue(ensureBody.contains("\"preserve_existing\":true"))
        assertTrue(!ensureBody.contains("\"title\""))
        assertTrue(!ensureBody.contains("\"body\""))
        val smokeBody = Buffer().also { prSmoke.body!!.writeTo(it) }.readUtf8()
        assertTrue(smokeBody.contains("\"smoke_test\":true"))
        assertTrue(!smokeBody.contains("\"title\""))
        assertTrue(!smokeBody.contains("\"body\""))
    }

    @Test fun installationTokenRequestUsesBrokerAndKeepsPairingSecretOutOfUrl() {
        val request = WorkspaceGitHubConnector.installationTokenRequest(pairing)
        assertEquals("POST", request.method)
        assertEquals("/github/token", request.url.encodedPath)
        assertTrue(!request.url.toString().contains(pairing))
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
