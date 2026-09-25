package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque
import java.util.Base64

class WorkspaceAgentReachGitHubRelevantRunnerTest {
    private class FakeExecutor : WorkspaceAgentReachGitHubRelevantRunner.Executor {
        data class Pending(
            val request: Request,
            val callback: (Result<Response>) -> Unit,
            var cancelled: Boolean = false,
        )
        val pending = ArrayDeque<Pending>()

        override fun enqueue(
            request: Request,
            callback: (Result<Response>) -> Unit,
        ): WorkspaceAgentReachGitHubRelevantRunner.Cancelable {
            val item = Pending(request, callback)
            pending.addLast(item)
            return object : WorkspaceAgentReachGitHubRelevantRunner.Cancelable {
                override fun cancel() { item.cancelled = true }
            }
        }

        fun respond(body: String, code: Int = 200) {
            val item = pending.removeFirst()
            item.callback(Result.success(
                Response.Builder()
                    .request(item.request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("test")
                    .body(body.toResponseBody())
                    .build()))
        }

        fun fail() {
            val item = pending.removeFirst()
            item.callback(Result.failure(IOException("offline")))
        }
    }

    private class Events : WorkspaceAgentReachGitHubRelevantRunner.Listener {
        val labels = mutableListOf<String>()
        var completion: WorkspaceAgentReachGitHubRelevantRunner.Completion? = null
        var error: String? = null

        override fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String?) {
            labels += label
        }
        override fun onComplete(completion: WorkspaceAgentReachGitHubRelevantRunner.Completion) {
            this.completion = completion
        }
        override fun onError(message: String) { error = message }
    }

    private fun pathMap(sha: String) = JSONObject()
        .put("sha", sha)
        .put("truncated", false)
        .put("tree", JSONArray()
            .put(JSONObject().put("path", "SKILL.md").put("type", "blob")
                .put("sha", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").put("size", 1_000))
            .put(JSONObject().put("path", "src/browser/security.py").put("type", "blob")
                .put("sha", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb").put("size", 2_000))
            .put(JSONObject().put("path", "README.md").put("type", "blob")
                .put("sha", "cccccccccccccccccccccccccccccccccccccccc").put("size", 500)))
        .toString()

    private fun file(sha: String, path: String, text: String) = JSONObject()
        .put("type", "file")
        .put("encoding", "base64")
        .put("size", text.toByteArray().size)
        .put("content", Base64.getEncoder().encodeToString(text.toByteArray()))
        .put("html_url", "https://github.com/a/b/blob/$sha/$path")
        .toString()

    @Test fun runnerReadsSelectedFilesSequentiallyAtSamePinnedRevision() {
        var target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val sha = "1234567890abcdef1234567890abcdef12345678"
        val executor = FakeExecutor()
        val events = Events()
        val runner = WorkspaceAgentReachGitHubRelevantRunner(
            currentTarget = { target },
            listener = events,
            executor = executor,
            nowMs = { 7L },
        )

        runner.start(target, sha, "check browser security and skill")
        assertEquals(1, executor.pending.size)
        assertTrue(executor.pending.first().request.url.toString().contains("/git/trees/$sha"))

        executor.respond(pathMap(sha))
        assertEquals(1, executor.pending.size)
        assertTrue(events.labels.last().contains("relevant GitHub file"))

        val firstPath = executor.pending.first().request.url.encodedPath
            .substringAfter("/contents/").replace("%20", " ")
        executor.respond(file(sha, firstPath, "first content"))
        assertEquals(1, executor.pending.size)

        val secondPath = executor.pending.first().request.url.encodedPath
            .substringAfter("/contents/").replace("%20", " ")
        executor.respond(file(sha, secondPath, "second content"))

        assertEquals(0, executor.pending.size)
        assertNull(events.error)
        val done = requireNotNull(events.completion)
        assertEquals(sha, done.commitSha)
        assertEquals(2, done.files.size)
        assertTrue(done.files.all { it.evidence.provenance.revision == sha })
        assertFalse(done.files.any { it.candidate.path == "README.md" })
    }

    @Test fun changedTargetStopsBeforeNextRead() {
        var target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val sha = "1234567890abcdef1234567890abcdef12345678"
        val executor = FakeExecutor()
        val events = Events()
        val runner = WorkspaceAgentReachGitHubRelevantRunner(
            currentTarget = { target }, listener = events, executor = executor)

        runner.start(target, sha, "check skill")
        target = WorkspaceAgentReachPolicy.parse("https://github.com/a/c")
        executor.respond(pathMap(sha))

        assertEquals(0, executor.pending.size)
        assertNotNull(events.error)
        assertTrue(events.error.orEmpty().contains("stale", ignoreCase = true))
    }

    @Test fun networkFailureDoesNotRetry() {
        val target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val sha = "1234567890abcdef1234567890abcdef12345678"
        val executor = FakeExecutor()
        val events = Events()
        val runner = WorkspaceAgentReachGitHubRelevantRunner(
            currentTarget = { target }, listener = events, executor = executor)

        runner.start(target, sha, "check skill")
        executor.fail()

        assertEquals(0, executor.pending.size)
        assertNotNull(events.error)
    }

    @Test fun provenanceMismatchStopsInsteadOfAcceptingWrongFile() {
        val target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val sha = "1234567890abcdef1234567890abcdef12345678"
        val executor = FakeExecutor()
        val events = Events()
        val runner = WorkspaceAgentReachGitHubRelevantRunner(
            currentTarget = { target }, listener = events, executor = executor)

        runner.start(target, sha, "check skill")
        executor.respond(pathMap(sha))
        assertEquals(1, executor.pending.size)
        executor.respond(file(sha, "WRONG.md", "wrong"))

        assertEquals(0, executor.pending.size)
        assertNotNull(events.error)
        assertTrue(events.error.orEmpty().contains("provenance", ignoreCase = true))
    }
}
