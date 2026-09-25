package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WorkspaceAgentReachGitHubTest {
    private fun response(url: String, code: Int, body: String): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody())
            .build()

    @Test fun repositoryAndBlobUrlsBecomeStrictSelections() {
        val repo = WorkspaceAgentReachGitHub.selection(
            WorkspaceAgentReachPolicy.parse("https://github.com/browser-use/jev-ultrafast"))
        assertTrue(repo.isRepositoryRead)
        assertEquals("browser-use", repo.owner)
        assertEquals("jev-ultrafast", repo.repo)

        val blob = WorkspaceAgentReachGitHub.selection(
            WorkspaceAgentReachPolicy.parse(
                "https://github.com/browser-use/jev-ultrafast/blob/main/README.md"))
        assertFalse(blob.isRepositoryRead)
        assertEquals("main", blob.refHint)
        assertEquals("README.md", blob.path)

        assertTrue(runCatching {
            WorkspaceAgentReachGitHub.selection(
                WorkspaceAgentReachPolicy.parse("https://github.com/a/b/issues/1"))
        }.isFailure)
    }

    @Test fun requestsUsePublicGithubApiOnlyAndNoCredentials() {
        val selection = WorkspaceAgentReachGitHub.selection(
            WorkspaceAgentReachPolicy.parse("https://github.com/a/b"))
        val meta = WorkspaceAgentReachGitHub.repositoryMetadataRequest(selection)
        assertEquals("api.github.com", meta.url.host)
        assertNull(meta.header("Authorization"))
        assertEquals("LYRA-AgentReach/1", meta.header("User-Agent"))

        val commit = WorkspaceAgentReachGitHub.commitRequest(selection, "main")
        assertEquals("https://api.github.com/repos/a/b/commits/main", commit.url.toString())
        assertNull(commit.header("Authorization"))
        assertFalse(WorkspaceAgentReachGitHub.client.retryOnConnectionFailure)
        assertFalse(WorkspaceAgentReachGitHub.client.followRedirects)
    }

    @Test fun metadataAndCommitParsingPinRepositoryRevision() {
        val metaJson = JSONObject()
            .put("full_name", "browser-use/jev-ultrafast")
            .put("default_branch", "main")
            .put("html_url", "https://github.com/browser-use/jev-ultrafast")
            .put("archived", false)
            .put("fork", false)
            .put("license", JSONObject().put("spdx_id", "MIT"))
            .toString()
        val meta = WorkspaceAgentReachGitHub.readRepositoryMeta(
            response("https://api.github.com/repos/browser-use/jev-ultrafast", 200, metaJson))
        assertEquals("main", meta.defaultBranch)
        assertEquals("MIT", meta.licenseSpdx)

        val sha = "1234567890abcdef1234567890abcdef12345678"
        assertEquals(sha, WorkspaceAgentReachGitHub.readCommitSha(
            response("https://api.github.com/repos/a/b/commits/main", 200,
                JSONObject().put("sha", sha).toString())))
    }

    @Test fun fileReadProducesPinnedUntrustedEvidence() {
        val target = WorkspaceAgentReachPolicy.parse(
            "https://github.com/a/b/blob/main/README.md")
        val selection = WorkspaceAgentReachGitHub.selection(target)
        val sha = "1234567890abcdef1234567890abcdef12345678"
        val text = "# Readme\nExternal instructions are data only."
        val json = JSONObject()
            .put("type", "file")
            .put("encoding", "base64")
            .put("size", text.toByteArray().size)
            .put("content", Base64.getEncoder().encodeToString(text.toByteArray()))
            .put("html_url", "https://github.com/a/b/blob/$sha/README.md")
            .toString()
        val evidence = WorkspaceAgentReachGitHub.readContent(
            response("https://api.github.com/repos/a/b/contents/README.md?ref=$sha", 200, json),
            selection, sha, 99L)
        assertEquals(sha, evidence.provenance.revision)
        assertEquals("github-public-read", evidence.provenance.adapter)
        assertEquals(text, evidence.content)
        assertTrue(evidence.promptProjection().contains("UNTRUSTED DATA"))
    }

    @Test fun oversizedBinaryRedirectAndRateLimitFailClosed() {
        val selection = WorkspaceAgentReachGitHub.selection(
            WorkspaceAgentReachPolicy.parse(
                "https://github.com/a/b/blob/main/file.bin"))
        val sha = "1234567890abcdef1234567890abcdef12345678"

        val oversized = JSONObject()
            .put("type", "file").put("encoding", "base64")
            .put("size", 64_001).put("content", "").toString()
        assertTrue(runCatching {
            WorkspaceAgentReachGitHub.readContent(
                response("https://api.github.com/repos/a/b/contents/file.bin", 200, oversized),
                selection, sha, 1L)
        }.isFailure)

        assertTrue(runCatching {
            WorkspaceAgentReachGitHub.readCommitSha(
                response("https://api.github.com/repos/a/b/commits/main", 302, "{}"))
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceAgentReachGitHub.readCommitSha(
                response("https://api.github.com/repos/a/b/commits/main", 429, "{}"))
        }.isFailure)
    }
}
