package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque
import java.util.Base64

class WorkspaceAgentReachGitHubRunnerTest {
    private class FakeExecutor : WorkspaceAgentReachGitHubRunner.Executor {
        data class Pending(
            val request: Request,
            val callback: (Result<Response>) -> Unit,
            var cancelled: Boolean = false,
        )

        val pending = ArrayDeque<Pending>()

        override fun enqueue(
            request: Request,
            callback: (Result<Response>) -> Unit,
        ): WorkspaceAgentReachGitHubRunner.Cancelable {
            val item = Pending(request, callback)
            pending.addLast(item)
            return object : WorkspaceAgentReachGitHubRunner.Cancelable {
                override fun cancel() { item.cancelled = true }
            }
        }

        fun respond(body: String, code: Int = 200) {
            val item = pending.removeFirst()
            val response = Response.Builder()
                .request(item.request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body.toResponseBody())
                .build()
            item.callback(Result.success(response))
        }

        fun fail() {
            val item = pending.removeFirst()
            item.callback(Result.failure(IOException("offline")))
        }
    }

    private class Events : WorkspaceAgentReachGitHubRunner.Listener {
        val phases = mutableListOf<WorkspaceWorkPhase>()
        val labels = mutableListOf<String>()
        var evidence: WorkspaceAgentReachEvidence.Evidence? = null
        var error: String? = null

        override fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String?) {
            phases += phase
            labels += label
        }

        override fun onComplete(evidence: WorkspaceAgentReachEvidence.Evidence) {
            this.evidence = evidence
        }

        override fun onError(message: String) {
            error = message
        }
    }

    private fun meta() = JSONObject()
        .put("full_name", "a/b")
        .put("default_branch", "main")
        .put("html_url", "https://github.com/a/b")
        .put("archived", false)
        .put("fork", false)
        .put("license", JSONObject().put("spdx_id", "MIT"))
        .toString()

    private fun commit(sha: String) = JSONObject().put("sha", sha).toString()

    private fun file(sha: String, text: String) = JSONObject()
        .put("type", "file")
        .put("encoding", "base64")
        .put("size", text.toByteArray().size)
        .put("content", Base64.getEncoder().encodeToString(text.toByteArray()))
        .put("html_url", "https://github.com/a/b/blob/$sha/README.md")
        .toString()

    @Test fun runnerExecutesExactlyOneRequestPerFreshPhase() {
        var target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val executor = FakeExecutor()
        val events = Events()
        val runner = WorkspaceAgentReachGitHubRunner(
            currentTarget = { target },
            listener = events,
            executor = executor,
            nowMs = { 123L },
        )
        val sha = "1234567890abcdef1234567890abcdef12345678"

        runner.start(target)
        assertEquals(1, executor.pending.size)
        assertTrue(events.phases.contains(WorkspaceWorkPhase.VISITING))
        assertTrue(events.phases.contains(WorkspaceWorkPhase.READING))

        executor.respond(meta())
        assertEquals(1, executor.pending.size)
        assertEquals(WorkspaceWorkPhase.VERIFYING, events.phases.last())

        executor.respond(commit(sha))
        assertEquals(1, executor.pending.size)
        assertEquals("Indexing repository", events.labels.last())

        val indexBody = org.json.JSONArray()
            .put(JSONObject().put("name", "README.md").put("path", "README.md").put("type", "file")
                .put("sha", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").put("size", 10))
            .put(JSONObject().put("name", "src").put("path", "src").put("type", "dir")
                .put("sha", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
            .toString()
        executor.respond(indexBody)
        assertEquals(1, executor.pending.size)
        assertEquals("Reading GitHub README", events.labels.last())

        executor.respond(file(sha, "# hello"))
        assertEquals(0, executor.pending.size)
        assertEquals(WorkspaceWorkPhase.DONE, events.phases.last())
        assertEquals(sha, events.evidence?.provenance?.revision)
        assertNull(events.error)
    }

    @Test fun changedTargetRejectsOldResponseAndDoesNotDispatchNextRequest() {
        var target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val executor = FakeExecutor()
        val events = Events()
        val runner = WorkspaceAgentReachGitHubRunner(
            currentTarget = { target }, listener = events, executor = executor)

        runner.start(target)
        target = WorkspaceAgentReachPolicy.parse("https://github.com/a/c")
        executor.respond(meta())

        assertEquals(0, executor.pending.size)
        assertNotNull(events.error)
        assertTrue(events.error.orEmpty().contains("stale", ignoreCase = true))
    }

    @Test fun networkFailureStopsWithoutRetry() {
        val target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val executor = FakeExecutor()
        val events = Events()
        val runner = WorkspaceAgentReachGitHubRunner(
            currentTarget = { target }, listener = events, executor = executor)

        runner.start(target)
        executor.fail()

        assertEquals(0, executor.pending.size)
        assertNotNull(events.error)
        assertEquals(WorkspaceWorkPhase.ERROR, events.phases.last())
    }

    @Test fun startingNewReadCancelsOldPendingCall() {
        val first = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        var current = first
        val executor = FakeExecutor()
        val events = Events()
        val runner = WorkspaceAgentReachGitHubRunner(
            currentTarget = { current }, listener = events, executor = executor)

        runner.start(first)
        val old = executor.pending.first()
        current = WorkspaceAgentReachPolicy.parse("https://github.com/a/c")
        runner.start(current)

        assertTrue(old.cancelled)
        assertEquals(2, executor.pending.size)
    }
}
