package com.myra.assistant.ui.workspace

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Bounded verified Actions lookup -> live release-tag commit -> published direct APK asset.
 * Uses existing paired GitHub read access. No mutation, workflow dispatch, artifact URL
 * guessing, provider call, redirect-following, or automatic retry is reachable here.
 */
internal class WorkspaceConnectedGitHubDownloadRunner(
    private val store: WorkspaceConnectorCredentialStore,
    private val listener: Listener,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class Completion(
        val repository: String,
        val branch: String,
        val run: WorkspaceGitHubConnector.WorkflowRun,
        val apkUrl: String,
    )

    interface Listener {
        fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String? = null)
        fun onComplete(completion: Completion)
        fun onError(message: String)
    }

    private var generation = 0L
    private var lookup: WorkspaceConnectedGitHubRunRunner? = null
    private var active: Call? = null

    @Synchronized fun start(runNumber: Long) {
        cancelLocked()
        require(runNumber > 0L) { "Build number is invalid" }
        val requestId = ++generation
        val newLookup = WorkspaceConnectedGitHubRunRunner(
            store = store,
            listener = object : WorkspaceConnectedGitHubRunRunner.Listener {
                override fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String?) {
                    synchronized(this@WorkspaceConnectedGitHubDownloadRunner) {
                        if (requestId != generation) return
                    }
                    listener.onEvent(phase, label, detail)
                }

                override fun onComplete(completion: WorkspaceConnectedGitHubRunRunner.Completion) {
                    verifyPublishedAsset(requestId, completion)
                }

                override fun onError(message: String) {
                    fail(requestId, message)
                }
            },
            nowMs = nowMs,
        )
        lookup = newLookup
        newLookup.start(runNumber)
    }

    private fun verifyPublishedAsset(
        requestId: Long,
        verified: WorkspaceConnectedGitHubRunRunner.Completion,
    ) {
        synchronized(this) { if (requestId != generation) return }
        val saved = store.loadGitHub()
        if (saved == null || !saved.repository.equals(verified.repository, ignoreCase = true) ||
            saved.branch != verified.branch ||
            saved.tokenExpiresAtMs?.let { it > nowMs() + 30_000L } != true) {
            fail(requestId, "Connected GitHub read access changed; reconnect and try again.")
            return
        }
        val token = saved.token
        val repo = verified.repository
        val sha = verified.run.headSha
        listener.onEvent(
            WorkspaceWorkPhase.VERIFYING,
            "Verifying live APK release",
            "Build #${verified.run.runNumber}",
        )
        dispatch(
            requestId,
            WorkspaceGitHubConnector.workflowReleaseTagRequest(token, repo, sha),
            "GitHub APK release tag could not be read.",
        ) { tagResponse ->
            WorkspaceGitHubConnector.verifyReleaseTagCommit(tagResponse, sha)
            dispatch(
                requestId,
                WorkspaceGitHubConnector.workflowReleaseRequest(token, repo, sha),
                "GitHub published APK release could not be read.",
            ) verifiedRelease@ { releaseResponse ->
                val asset = WorkspaceGitHubConnector.readVerifiedApkRelease(
                    releaseResponse, repo, sha
                )
                synchronized(this) { if (requestId != generation) return@verifiedRelease }
                listener.onEvent(
                    WorkspaceWorkPhase.DONE,
                    "Direct APK link verified",
                    "Build #${verified.run.runNumber} · ${asset.tag}",
                )
                listener.onComplete(Completion(repo, verified.branch, verified.run, asset.url))
            }
        }
    }

    @Synchronized fun cancel() {
        ++generation
        cancelLocked()
    }

    private fun cancelLocked() {
        lookup?.cancel()
        lookup = null
        active?.cancel()
        active = null
    }

    private fun dispatch(
        requestId: Long,
        request: Request,
        onNetworkFailure: String,
        consume: (Response) -> Unit,
    ) {
        val call = WorkspaceGitHubConnector.client.newCall(request)
        synchronized(this) {
            if (requestId != generation) return
            active = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                synchronized(this@WorkspaceConnectedGitHubDownloadRunner) {
                    if (requestId != generation || active !== call) return
                    active = null
                }
                fail(requestId, onNetworkFailure)
            }

            override fun onResponse(call: Call, response: Response) {
                synchronized(this@WorkspaceConnectedGitHubDownloadRunner) {
                    if (requestId != generation || active !== call) {
                        response.close()
                        return
                    }
                    active = null
                }
                runCatching { response.use(consume) }.onFailure {
                    fail(requestId, it.message ?: "GitHub direct APK verification stopped.")
                }
            }
        })
    }

    private fun fail(requestId: Long, message: String) {
        synchronized(this) {
            if (requestId != generation) return
            active = null
        }
        listener.onEvent(WorkspaceWorkPhase.ERROR, "APK download lookup stopped", message)
        listener.onError(message)
    }
}