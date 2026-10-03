package com.myra.assistant.ui.workspace

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Executes only the bounded relevant-file plan, one public GET at a time.
 *
 * No retry, parallel crawl, credentials, write, install, execution or provider projection.
 */
internal class WorkspaceAgentReachGitHubRelevantRunner(
    private val currentTarget: () -> WorkspaceAgentReachPolicy.Target?,
    private val listener: Listener,
    private val executor: Executor = OkHttpExecutor,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    interface Cancelable { fun cancel() }

    fun interface Executor {
        fun enqueue(request: Request, callback: (Result<Response>) -> Unit): Cancelable
    }

    data class FileEvidence(
        val candidate: WorkspaceAgentReachGitHubRelevance.Candidate,
        val evidence: WorkspaceAgentReachEvidence.Evidence,
    )

    data class Completion(
        val commitSha: String,
        val pathMap: WorkspaceAgentReachGitHub.RepositoryPathMap,
        val files: List<FileEvidence>,
        val repoIndex: WorkspaceAgentReachGitHubRepoIndex.Index =
            WorkspaceAgentReachGitHubRepoIndex.build(pathMap),
    )

    interface Listener {
        fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String? = null)
        fun onComplete(completion: Completion)
        fun onError(message: String)
    }

    private object OkHttpExecutor : Executor {
        override fun enqueue(
            request: Request,
            callback: (Result<Response>) -> Unit,
        ): Cancelable {
            val call = WorkspaceAgentReachGitHub.client.newCall(request)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    callback(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    callback(Result.success(response))
                }
            })
            return object : Cancelable {
                override fun cancel() = call.cancel()
            }
        }
    }

    private var generation = 0L
    private var active: Cancelable? = null
    private var target: WorkspaceAgentReachPolicy.Target? = null
    private var query = ""
    private var commitSha = ""
    private var plan: WorkspaceAgentReachGitHubRelevantPlan.Plan? = null
    private var nextFileIndex = 0
    private val evidence = mutableListOf<FileEvidence>()

    @Synchronized fun start(
        target: WorkspaceAgentReachPolicy.Target,
        commitSha: String,
        userRequest: String,
    ) {
        cancelLocked()
        val run = ++generation
        this.target = target
        this.query = userRequest
        this.commitSha = commitSha
        this.plan = null
        this.nextFileIndex = 0
        this.evidence.clear()
        listener.onEvent(
            WorkspaceWorkPhase.READING,
            "Mapping relevant GitHub files",
            commitSha.take(12),
        )
        val request = WorkspaceAgentReachGitHubRelevantPlan.pathMapRequest(target, commitSha)
        dispatch(run, request) { response -> acceptMap(run, response) }
    }

    @Synchronized fun cancel() {
        ++generation
        cancelLocked()
        clearState()
    }

    private fun cancelLocked() {
        active?.cancel()
        active = null
    }

    private fun clearState() {
        target = null
        query = ""
        commitSha = ""
        plan = null
        nextFileIndex = 0
        evidence.clear()
    }

    private fun currentOrFail(run: Long): WorkspaceAgentReachPolicy.Target? {
        val expected = synchronized(this) { target }
        val current = currentTarget()
        if (run != synchronized(this) { generation } || expected == null || current == null ||
            current.canonicalUrl != expected.canonicalUrl) {
            fail(run, "GitHub relevant-file target changed; stale result was discarded.")
            return null
        }
        return current
    }

    @Synchronized private fun dispatch(
        run: Long,
        request: Request,
        accept: (Response) -> Unit,
    ) {
        if (run != generation) return
        active = executor.enqueue(request) { result ->
            synchronized(this) {
                if (run != generation) {
                    result.getOrNull()?.close()
                    return@enqueue
                }
                active = null
            }
            val response = result.getOrElse {
                fail(run, "GitHub relevant-file read failed. No retry was sent.")
                return@enqueue
            }
            if (currentOrFail(run) == null) {
                response.close()
                return@enqueue
            }
            runCatching { accept(response) }
                .onFailure {
                    response.close()
                    fail(run, it.message ?: "GitHub relevant-file read stopped safely.")
                }
        }
    }

    private fun acceptMap(run: Long, response: Response) {
        val expected = synchronized(this) { requireNotNull(target) }
        val built = WorkspaceAgentReachGitHubRelevantPlan.build(
            target = expected,
            commitSha = synchronized(this) { commitSha },
            userRequest = synchronized(this) { query },
            response = response,
        )
        synchronized(this) {
            if (run != generation) return
            plan = built
            nextFileIndex = 0
        }
        if (built.files.isEmpty()) complete(run, built) else readNext(run, built)
    }

    private fun readNext(
        run: Long,
        built: WorkspaceAgentReachGitHubRelevantPlan.Plan,
    ) {
        val index = synchronized(this) { nextFileIndex }
        if (index >= built.files.size) {
            complete(run, built)
            return
        }
        val item = built.files[index]
        listener.onEvent(
            WorkspaceWorkPhase.READING,
            "Reading relevant GitHub file",
            "${index + 1}/${built.files.size} · ${item.candidate.path}",
        )
        dispatch(run, item.request) { response ->
            val fileEvidence = WorkspaceAgentReachGitHub.readPinnedRepositoryContent(
                response = response,
                selection = built.selection,
                commitSha = built.commitSha,
                expectedPath = item.candidate.path,
                fetchedAtMs = nowMs(),
            )
            require(fileEvidence.provenance.revision == built.commitSha) {
                "Relevant GitHub file revision changed"
            }
            synchronized(this) {
                if (run != generation) return@dispatch
                evidence += FileEvidence(item.candidate, fileEvidence)
                nextFileIndex++
            }
            readNext(run, built)
        }
    }

    private fun complete(
        run: Long,
        built: WorkspaceAgentReachGitHubRelevantPlan.Plan,
    ) {
        val result = synchronized(this) {
            if (run != generation) return
            Completion(
                commitSha = built.commitSha,
                pathMap = built.pathMap,
                files = evidence.toList(),
                repoIndex = built.repoIndex,
            )
        }
        val noun = if (result.files.size == 1) "file" else "files"
        listener.onEvent(
            WorkspaceWorkPhase.DONE,
            "Relevant GitHub files ready",
            "${result.files.size} bounded $noun",
        )
        listener.onComplete(result)
        synchronized(this) {
            if (run == generation) {
                active = null
                clearState()
            }
        }
    }

    private fun fail(run: Long, message: String) {
        synchronized(this) {
            if (run != generation) return
            active = null
            clearState()
        }
        listener.onEvent(WorkspaceWorkPhase.ERROR, "GitHub relevant read stopped", message)
        listener.onError(message)
    }
}
