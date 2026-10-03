package com.myra.assistant.ui.workspace

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/**
 * H9 two-read pinned revalidation runner.
 *
 * It issues only GETs for the already-approved immutable SHA. No branch resolution, retry, redirect,
 * token, clone, archive, provider/model projection, execution or persistence occurs here.
 */
internal class WorkspaceSkillGitHubInstallRunner(
    private val listener: Listener,
) {
    interface Listener {
        fun onStage(message: String)
        fun onValidated(validated: WorkspaceSkillGitHubInstallApproval.Validated)
        fun onError(message: String)
    }

    private var generation = 0L
    private var activeCall: Call? = null

    @Synchronized fun start(prepared: WorkspaceSkillGitHubInstallApproval.Prepared) {
        generation += 1
        activeCall?.cancel()
        activeCall = null
        val run = generation
        listener.onStage("Re-reading approved pinned SKILL.md")
        dispatchSkill(run, prepared)
    }

    @Synchronized fun cancel() {
        generation += 1
        activeCall?.cancel()
        activeCall = null
    }

    private fun dispatchSkill(
        run: Long,
        prepared: WorkspaceSkillGitHubInstallApproval.Prepared,
    ) {
        val call = WorkspaceAgentReachGitHub.client.newCall(
            WorkspaceSkillGitHubInstallApproval.firstRequest(prepared))
        synchronized(this) {
            if (run != generation) {
                call.cancel()
                return
            }
            activeCall = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) =
                fail(run, call, e)

            override fun onResponse(call: Call, response: Response) {
                runCatching {
                    requireCurrent(run)
                    WorkspaceSkillGitHubInstallApproval.acceptSkill(
                        prepared, response, System.currentTimeMillis())
                }.onSuccess { next ->
                    listener.onStage("Re-checking approved pinned skill.json")
                    dispatchManifest(run, next)
                }.onFailure { error ->
                    runCatching { response.close() }
                    fail(run, error.message ?: "Pinned GitHub skill revalidation failed")
                }
            }
        })
    }

    private fun dispatchManifest(
        run: Long,
        next: WorkspaceSkillGitHubInstallApproval.Next,
    ) {
        val call = WorkspaceAgentReachGitHub.client.newCall(next.request)
        synchronized(this) {
            if (run != generation) {
                call.cancel()
                return
            }
            activeCall = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) =
                fail(run, call, e)

            override fun onResponse(call: Call, response: Response) {
                runCatching {
                    requireCurrent(run)
                    WorkspaceSkillGitHubInstallApproval.acceptManifest(
                        next.state, response, System.currentTimeMillis())
                }.onSuccess { validated ->
                    synchronized(this@WorkspaceSkillGitHubInstallRunner) {
                        if (run != generation) return@onSuccess
                        activeCall = null
                    }
                    listener.onValidated(validated)
                }.onFailure { error ->
                    runCatching { response.close() }
                    fail(run, error.message ?: "Pinned GitHub install revalidation failed")
                }
            }
        })
    }

    @Synchronized private fun requireCurrent(run: Long) {
        require(run == generation) { "Stale GitHub install revalidation was discarded" }
    }

    private fun fail(run: Long, call: Call, error: IOException) {
        fail(
            run,
            if (call.isCanceled()) "GitHub install revalidation was cancelled"
            else "GitHub pinned read failed without retry: " +
                (error.message ?: "network failure"),
        )
    }

    private fun fail(run: Long, message: String) {
        synchronized(this) {
            if (run != generation) return
            activeCall = null
        }
        listener.onError(message)
    }
}
