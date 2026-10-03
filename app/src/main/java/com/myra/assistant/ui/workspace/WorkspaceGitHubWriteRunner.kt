package com.myra.assistant.ui.workspace

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Executes one explicitly prepared C2 broker request at a time.
 *
 * No retries, no write token on Android, no main/master mutation, no merge and no destructive calls.
 */
internal class WorkspaceGitHubWriteRunner(
    private val listener: Listener,
    private val executor: Executor = OkHttpExecutor,
) {
    interface Cancelable { fun cancel() }

    fun interface Executor {
        fun enqueue(request: Request, callback: (Result<Response>) -> Unit): Cancelable
    }

    interface Listener {
        fun onWriteAccess(access: WorkspaceGitHubConnector.WriteAccess) {}
        fun onCommit(receipt: WorkspaceGitHubConnector.CommitReceipt) {}
        fun onPullRequest(receipt: WorkspaceGitHubConnector.PullRequestReceipt) {}
        fun onError(message: String)
    }

    private object OkHttpExecutor : Executor {
        override fun enqueue(
            request: Request,
            callback: (Result<Response>) -> Unit,
        ): Cancelable {
            val call = WorkspaceGitHubConnector.client.newCall(request)
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

    @Synchronized fun verify(pairingSecret: String) {
        dispatch(
            WorkspaceGitHubConnector.writeAccessRequest(pairingSecret)
        ) { response -> listener.onWriteAccess(WorkspaceGitHubConnector.readWriteAccess(response)) }
    }

    @Synchronized fun commit(
        pairingSecret: String,
        plan: WorkspaceGitHubWritePolicy.CommitPlan,
    ) {
        dispatch(
            WorkspaceGitHubConnector.commitRequest(pairingSecret, plan)
        ) { response -> listener.onCommit(WorkspaceGitHubConnector.readCommitReceipt(response)) }
    }

    @Synchronized fun ensureDraftPullRequest(
        pairingSecret: String,
        plan: WorkspaceGitHubWritePolicy.PullRequestPlan,
    ) {
        dispatch(
            WorkspaceGitHubConnector.pullRequestRequest(pairingSecret, plan)
        ) { response ->
            listener.onPullRequest(WorkspaceGitHubConnector.readPullRequestReceipt(response))
        }
    }

    @Synchronized fun smokeTestDraftPullRequest(pairingSecret: String) {
        dispatch(
            WorkspaceGitHubConnector.pullRequestSmokeTestRequest(pairingSecret)
        ) { response ->
            listener.onPullRequest(WorkspaceGitHubConnector.readPullRequestReceipt(response))
        }
    }

    @Synchronized fun cancel() {
        generation += 1
        active?.cancel()
        active = null
    }

    private fun dispatch(request: Request, accept: (Response) -> Unit) {
        active?.cancel()
        active = null
        val run = ++generation
        active = executor.enqueue(request) { result ->
            synchronized(this) {
                if (run != generation) {
                    result.getOrNull()?.close()
                    return@enqueue
                }
                active = null
            }
            val response = result.getOrElse {
                listener.onError("GitHub write broker could not be reached. No retry was sent.")
                return@enqueue
            }
            runCatching { accept(response) }
                .onFailure { error ->
                    runCatching { response.close() }
                    listener.onError(error.message ?: "GitHub write request stopped safely.")
                }
        }
    }
}
