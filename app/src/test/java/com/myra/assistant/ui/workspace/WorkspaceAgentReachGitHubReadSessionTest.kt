package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WorkspaceAgentReachGitHubReadSessionTest {
    private fun response(url: String, code: Int = 200, body: String): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody())
            .build()

    private fun meta(
        fullName: String = "a/b",
        branch: String = "main",
    ): String = JSONObject()
        .put("full_name", fullName)
        .put("default_branch", branch)
        .put("html_url", "https://github.com/a/b")
        .put("archived", false)
        .put("fork", false)
        .put("license", JSONObject().put("spdx_id", "MIT"))
        .toString()

    private fun commit(sha: String): String = JSONObject().put("sha", sha).toString()

    private fun file(sha: String, text: String, path: String = "README.md"): String =
        JSONObject()
            .put("type", "file")
            .put("encoding", "base64")
            .put("size", text.toByteArray().size)
            .put("content", Base64.getEncoder().encodeToString(text.toByteArray()))
            .put("html_url", "https://github.com/a/b/blob/$sha/$path")
            .toString()

    @Test fun repositoryReadPinsDefaultBranchBeforeReadmeEvidence() {
        val target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val sha = "1234567890abcdef1234567890abcdef12345678"

        val started = WorkspaceAgentReachGitHubReadSession.start(target)
        assertEquals(WorkspaceAgentReachGitHubReadSession.Phase.AWAITING_METADATA,
            started.state.phase)
        assertEquals("https://api.github.com/repos/a/b", started.request.url.toString())

        val afterMeta = WorkspaceAgentReachGitHubReadSession.acceptMetadata(
            started.state, target,
            response(started.request.url.toString(), body = meta()))
        assertEquals(WorkspaceAgentReachGitHubReadSession.Phase.AWAITING_COMMIT,
            afterMeta.state.phase)
        assertEquals("main", afterMeta.state.ref)

        val afterCommit = WorkspaceAgentReachGitHubReadSession.acceptCommit(
            afterMeta.state, target,
            response(afterMeta.request.url.toString(), body = commit(sha)))
        assertEquals(WorkspaceAgentReachGitHubReadSession.Phase.AWAITING_CONTENT,
            afterCommit.state.phase)
        assertTrue(afterCommit.request.url.toString().contains("/readme?ref=$sha"))

        val done = WorkspaceAgentReachGitHubReadSession.acceptContent(
            afterCommit.state, target,
            response(afterCommit.request.url.toString(), body = file(sha, "# Hello")),
            fetchedAtMs = 123L)
        assertEquals(WorkspaceAgentReachGitHubReadSession.Phase.COMPLETE, done.phase)
        assertEquals(sha, done.evidence?.provenance?.revision)
        assertEquals("MIT", done.repositoryMeta?.licenseSpdx)
        assertTrue(done.evidence?.promptProjection().orEmpty().contains("UNTRUSTED DATA"))
    }

    @Test fun fileReadSkipsMetadataButStillPinsCommit() {
        val target = WorkspaceAgentReachPolicy.parse(
            "https://github.com/a/b/blob/main/src/App.kt")
        val sha = "1234567890abcdef1234567890abcdef12345678"

        val started = WorkspaceAgentReachGitHubReadSession.start(target)
        assertEquals(WorkspaceAgentReachGitHubReadSession.Phase.AWAITING_COMMIT,
            started.state.phase)
        assertTrue(started.request.url.toString().endsWith("/commits/main"))

        val afterCommit = WorkspaceAgentReachGitHubReadSession.acceptCommit(
            started.state, target,
            response(started.request.url.toString(), body = commit(sha)))
        assertTrue(afterCommit.request.url.toString()
            .contains("/contents/src/App.kt?ref=$sha"))
    }

    @Test fun staleTargetIsRejectedBetweenEveryPhase() {
        val target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val other = WorkspaceAgentReachPolicy.parse("https://github.com/a/c")
        val started = WorkspaceAgentReachGitHubReadSession.start(target)

        assertTrue(runCatching {
            WorkspaceAgentReachGitHubReadSession.acceptMetadata(
                started.state, other,
                response(started.request.url.toString(), body = meta()))
        }.isFailure)
    }

    @Test fun mismatchedRepositoryIdentityIsRejected() {
        val target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val started = WorkspaceAgentReachGitHubReadSession.start(target)
        assertTrue(runCatching {
            WorkspaceAgentReachGitHubReadSession.acceptMetadata(
                started.state, target,
                response(started.request.url.toString(), body = meta(fullName = "evil/other")))
        }.isFailure)
    }

    @Test fun slashContainingDefaultBranchIsEncodedAndSupported() {
        val target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val started = WorkspaceAgentReachGitHubReadSession.start(target)
        val afterMeta = WorkspaceAgentReachGitHubReadSession.acceptMetadata(
            started.state, target,
            response(started.request.url.toString(), body = meta(branch = "release/2026")))
        assertTrue(afterMeta.request.url.toString().contains("release%2F2026"))
        assertEquals("release/2026", afterMeta.state.ref)
    }
}
