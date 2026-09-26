package com.myra.assistant.ui.workspace

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/**
 * Executes one H8 read session with the existing Agent Reach public GitHub client.
 *
 * No retry, redirect, auth, clone, archive, install, provider projection or persistence.
 */
internal class WorkspaceSkillGitHubPreviewRunner(
    private val listener: Listener,
) {
    interface Listener {
        fun onStage(message: String)
        fun onComplete(completion: WorkspaceSkillGitHubReadSession.Completion)
        fun onError(message: String)
    }

    private var generation = 0L
    private var activeCall: Call? = null

    @Synchronized fun start(url: String) {
        generation += 1
        activeCall?.cancel()
        activeCall = null
        val run = generation
        val first = runCatching { WorkspaceSkillGitHubReadSession.start(url) }
            .getOrElse {
                listener.onError(it.message ?: "GitHub skill URL was rejected")
                return
            }
        listener.onStage("Resolving GitHub skill")
        dispatch(run, first)
    }

    @Synchronized fun cancel() {
        generation += 1
        activeCall?.cancel()
        activeCall = null
    }

    private fun dispatch(
        run: Long,
        step: WorkspaceSkillGitHubReadSession.Step,
    ) {
        val call = WorkspaceAgentReachGitHub.client.newCall(step.request)
        synchronized(this) {
            if (run != generation) {
                call.cancel()
                return
            }
            activeCall = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@WorkspaceSkillGitHubPreviewRunner) {
                    if (run != generation) return
                    activeCall = null
                }
                listener.onError(
                    if (call.isCanceled()) "GitHub skill preview was cancelled"
                    else "GitHub skill read failed without retry: " +
                        (e.message ?: "network failure")
                )
            }

            override fun onResponse(call: Call, response: Response) {
                val next = runCatching {
                    synchronized(this@WorkspaceSkillGitHubPreviewRunner) {
                        require(run == generation) {
                            "Stale GitHub skill response was discarded"
                        }
                    }
                    when (step.state.phase) {
                        WorkspaceSkillGitHubReadSession.Phase.AWAITING_METADATA -> {
                            listener.onStage("Pinning default branch")
                            WorkspaceSkillGitHubReadSession.acceptMetadata(step.state, response)
                        }
                        WorkspaceSkillGitHubReadSession.Phase.AWAITING_COMMIT -> {
                            listener.onStage("Reading pinned SKILL.md")
                            WorkspaceSkillGitHubReadSession.acceptCommit(step.state, response)
                        }
                        WorkspaceSkillGitHubReadSession.Phase.AWAITING_SKILL -> {
                            listener.onStage("Checking optional skill.json")
                            WorkspaceSkillGitHubReadSession.acceptSkill(
                                step.state, response, System.currentTimeMillis())
                        }
                        WorkspaceSkillGitHubReadSession.Phase.AWAITING_MANIFEST -> {
                            val done = WorkspaceSkillGitHubReadSession.acceptManifest(
                                step.state, response, System.currentTimeMillis())
                            synchronized(this@WorkspaceSkillGitHubPreviewRunner) {
                                if (run != generation) {
                                    throw IllegalStateException(
                                        "Stale GitHub skill completion was discarded")
                                }
                                activeCall = null
                            }
                            listener.onComplete(done)
                            null
                        }
                        WorkspaceSkillGitHubReadSession.Phase.COMPLETE ->
                            error("GitHub skill read was already complete")
                    }
                }
                next.onSuccess { more ->
                    if (more != null) dispatch(run, more)
                }.onFailure { error ->
                    runCatching { response.close() }
                    synchronized(this@WorkspaceSkillGitHubPreviewRunner) {
                        if (run != generation) return@onFailure
                        activeCall = null
                    }
                    listener.onError(
                        error.message ?: "GitHub skill preview stopped safely")
                }
            }
        })
    }
}
