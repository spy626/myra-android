package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
        val runs = WorkspaceGitHubConnector.workflowRunsRequest(
            token,
            "spy626/myra-android",
            "agent/myra-phase-1",
            head,
        )
        val recentRuns = WorkspaceGitHubConnector.workflowRunsForBranchRequest(
            token,
            "spy626/myra-android",
            "agent/myra-phase-1",
        )
        val jobs = WorkspaceGitHubConnector.workflowRunJobsRequest(
            token,
            "spy626/myra-android",
            123L,
        )
        listOf(repo, branch, runs, recentRuns, jobs).forEach { request ->
            assertEquals("https", request.url.scheme)
            assertEquals("api.github.com", request.url.host)
            assertTrue(request.url.toString().contains(token).not())
            assertEquals("Bearer $token", request.header("Authorization"))
        }
        assertTrue(branch.url.encodedPath.contains("agent%2Fmyra-phase-1"))
        assertTrue(runs.url.encodedPath.endsWith("/actions/runs"))
        assertEquals("push", runs.url.queryParameter("event"))
        assertEquals("agent/myra-phase-1", runs.url.queryParameter("branch"))
        assertEquals(head, runs.url.queryParameter("head_sha"))
        assertEquals("5", runs.url.queryParameter("per_page"))
        assertEquals("push", recentRuns.url.queryParameter("event"))
        assertEquals("agent/myra-phase-1", recentRuns.url.queryParameter("branch"))
        assertEquals("100", recentRuns.url.queryParameter("per_page"))
        assertTrue(recentRuns.url.queryParameter("head_sha") == null)
        assertTrue(jobs.url.encodedPath.endsWith("/actions/runs/123/jobs"))
    }

    private fun response(json: String): Response = Response.Builder()
        .request(Request.Builder()
            .url("https://api.github.com/repos/spy626/myra-android/actions/runs")
            .build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(json.toResponseBody("application/json".toMediaType()))
        .build()

    @Test fun exactRunNumberReadIsBoundToConnectedFeatureBranchAndWorkflow() {
        val wanted = "1234567890abcdef1234567890abcdef12345678"
        val other = "abcdef1234567890abcdef1234567890abcdef12"
        val run = WorkspaceGitHubConnector.readWorkflowRunByNumber(
            response(
                """{"workflow_runs":[
                    {"id":1,"run_number":3324,"name":"Build Android APK","head_branch":"other/branch","head_sha":"$other","status":"completed","conclusion":"success","html_url":"https://github.com/spy626/myra-android/actions/runs/1"},
                    {"id":2,"run_number":3324,"name":"Other Workflow","head_branch":"agent/myra-phase-1","head_sha":"$other","status":"completed","conclusion":"success","html_url":"https://github.com/spy626/myra-android/actions/runs/2"},
                    {"id":3,"run_number":3324,"name":"Build Android APK","head_branch":"agent/myra-phase-1","head_sha":"$wanted","status":"completed","conclusion":"success","html_url":"https://github.com/spy626/myra-android/actions/runs/3"}
                ]}"""
            ),
            expectedRunNumber = 3324L,
            expectedBranch = "agent/myra-phase-1",
        )

        assertEquals(3324L, run?.runNumber)
        assertEquals("success", run?.conclusion)
        assertEquals(wanted, run?.headSha)
        assertEquals(3L, run?.id)
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
        // main/master remain forbidden as the configured WRITE feature branch, not as reads.
        assertTrue(runCatching {
            WorkspaceConnectorPolicy.binding("spy626/myra-android", "main")
        }.isFailure)
    }

    @Test fun liveMainBranchReadUsesGetAndValidatedHeadWithoutGrantingMainWrites() {
        val request = WorkspaceGitHubConnector.branchRequest(
            token, "spy626/myra-android", "main"
        )
        assertEquals("GET", request.method)
        assertEquals("api.github.com", request.url.host)
        assertTrue(request.url.encodedPath.endsWith("/branches/main"))
        assertTrue(!request.url.toString().contains(token))
        assertEquals("Bearer $token", request.header("Authorization"))

        val value = WorkspaceGitHubConnector.readBranch(
            response("""{"name":"main","commit":{"sha":"$head"}}"""),
            "main",
        )
        assertEquals("main", value.name)
        assertEquals(head, value.headSha)
        assertTrue(runCatching {
            WorkspaceGitHubConnector.readBranch(
                response("""{"name":"other","commit":{"sha":"$head"}}"""), "main"
            )
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceConnectorPolicy.requireFeatureBranch("main")
        }.isFailure)
    }
}
