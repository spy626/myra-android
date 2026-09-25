package com.myra.assistant.ui.workspace

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Executes the freshness-bound public GitHub read one GET at a time.
 *
 * No retry, parallel crawl, login, write, install or script execution. UI callbacks are intentionally
 * transport-thread callbacks; WorkspaceActivity may marshal them to the main thread when wired later.
 */
internal class WorkspaceAgentReachGitHubRunner(
    private val currentTarget: () -> WorkspaceAgentReachPolicy.Target?,
    private val listener: Listener,
    private val executor: Executor = OkHttpExecutor,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    interface Cancelable {
        fun cancel()
    }

    fun interface Executor {
        fun enqueue(request: Request, callback: (Result<Response>) -> Unit): Cancelable
    }

    interface Listener {
        fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String? = null)
        fun onComplete(evidence: WorkspaceAgentReachEvidence.Evidence)
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

    @Synchronized fun start(target: WorkspaceAgentReachPolicy.Target) {
        cancelLocked()
        val run = ++generation
        val step = WorkspaceAgentReachGitHubReadSession.start(target)
        listener.onEvent(
            WorkspaceWorkPhase.VISITING,
            "Opening GitHub",
            "${step.state.selection.owner}/${step.state.selection.repo}",
        )
        dispatch(run, step)
    }

    @Synchronized fun cancel() {
        ++generation
        cancelLocked()
    }

    private fun cancelLocked() {
        active?.cancel()
        active = null
    }

    private fun eventFor(step: WorkspaceAgentReachGitHubReadSession.Step) {
        val state = step.state
        when (state.phase) {
            WorkspaceAgentReachGitHubReadSession.Phase.AWAITING_METADATA ->
                listener.onEvent(
                    WorkspaceWorkPhase.READING,
                    "Reading repository metadata",
                    "${state.selection.owner}/${state.selection.repo}",
                )
            WorkspaceAgentReachGitHubReadSession.Phase.AWAITING_COMMIT ->
                listener.onEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "Pinning GitHub revision",
                    state.ref,
                )
            WorkspaceAgentReachGitHubReadSession.Phase.AWAITING_CONTENT ->
                listener.onEvent(
                    WorkspaceWorkPhase.READING,
                    if (state.selection.isRepositoryRead) "Reading GitHub README"
                    else "Reading GitHub file",
                    state.selection.path ?: "README",
                )
            WorkspaceAgentReachGitHubReadSession.Phase.COMPLETE -> Unit
        }
    }

    @Synchronized private fun dispatch(
        run: Long,
        step: WorkspaceAgentReachGitHubReadSession.Step,
    ) {
        if (run != generation) return
        eventFor(step)
        active = executor.enqueue(step.request) { result ->
            handle(run, step.state, result)
        }
    }

    private fun handle(
        run: Long,
        state: WorkspaceAgentReachGitHubReadSession.State,
        result: Result<Response>,
    ) {
        synchronized(this) {
            if (run != generation) {
                result.getOrNull()?.close()
                return
            }
            active = null
        }

        val response = result.getOrElse {
            fail(run, "GitHub read failed before a usable response. No retry was sent.")
            return
        }
        val current = currentTarget()
        if (current == null) {
            response.close()
            fail(run, "GitHub read target is no longer current; stale result was discarded.")
            return
        }

        runCatching<WorkspaceAgentReachGitHubReadSession.Step?> {
            when (state.phase) {
                WorkspaceAgentReachGitHubReadSession.Phase.AWAITING_METADATA ->
                    WorkspaceAgentReachGitHubReadSession.acceptMetadata(
                        state, current, response)
                WorkspaceAgentReachGitHubReadSession.Phase.AWAITING_COMMIT ->
                    WorkspaceAgentReachGitHubReadSession.acceptCommit(
                        state, current, response)
                WorkspaceAgentReachGitHubReadSession.Phase.AWAITING_CONTENT -> {
                    val done = WorkspaceAgentReachGitHubReadSession.acceptContent(
                        state, current, response, nowMs())
                    synchronized(this) {
                        if (run != generation) return@runCatching null
                    }
                    listener.onEvent(
                        WorkspaceWorkPhase.DONE,
                        "GitHub read complete",
                        done.commitSha?.take(12),
                    )
                    listener.onComplete(requireNotNull(done.evidence))
                    null
                }
                WorkspaceAgentReachGitHubReadSession.Phase.COMPLETE ->
                    error("GitHub read was already complete")
            }
        }.onSuccess { next ->
            if (next != null) {
                synchronized(this) {
                    if (run != generation) return@onSuccess
                    dispatch(run, next)
                }
            }
        }.onFailure { failure ->
            response.close()
            fail(run, failure.message ?: "GitHub read stopped safely.")
        }
    }

    private fun fail(run: Long, message: String) {
        synchronized(this) {
            if (run != generation) return
            active = null
        }
        listener.onEvent(WorkspaceWorkPhase.ERROR, "GitHub read stopped", message)
        listener.onError(message)
    }
}
