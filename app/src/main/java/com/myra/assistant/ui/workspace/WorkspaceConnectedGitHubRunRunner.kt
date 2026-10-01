package com.myra.assistant.ui.workspace

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * One-shot, read-only resolver for a recent GitHub Actions run in the connected feature branch.
 *
 * It refreshes only the installation read token, lists branch push runs and returns one exact
 * run number. It never calls write, commit, pull-request, rerun or workflow-dispatch endpoints.
 */
internal class WorkspaceConnectedGitHubRunRunner(
    private val store: WorkspaceConnectorCredentialStore,
    private val listener: Listener,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class Completion(
        val repository: String,
        val branch: String,
        val run: WorkspaceGitHubConnector.WorkflowRun,
    )

    interface Listener {
        fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String? = null)
        fun onComplete(completion: Completion)
        fun onError(message: String)
    }

    private var generation = 0L
    private var active: Call? = null

    @Synchronized fun start(runNumber: Long) {
        cancelLocked()
        require(runNumber > 0L) { "GitHub workflow run number is invalid" }
        val run = ++generation
        val saved = store.loadGitHub()
        if (saved == null) {
            fail(run, "Connect the LYRA GitHub App first. No repository change was attempted.")
            return
        }
        val secret = saved.pairingSecret
        if (secret != null) {
            listener.onEvent(
                WorkspaceWorkPhase.READING,
                "Refreshing GitHub read access",
                saved.branch,
            )
            dispatch(
                run,
                WorkspaceGitHubConnector.installationTokenRequest(secret),
                "GitHub read-token refresh failed; no repository change was attempted.",
            ) { response ->
                val fresh = WorkspaceGitHubConnector.readInstallationGrant(response)
                require(fresh.repository.equals(saved.repository, ignoreCase = true) &&
                    fresh.branch == saved.branch &&
                    fresh.login.equals(saved.login, ignoreCase = true)) {
                    "Fresh GitHub installation binding changed; build verification stopped"
                }
                val expiry = Math.addExact(
                    nowMs(),
                    Math.multiplyExact(fresh.expiresInSeconds, 1_000L),
                )
                store.saveGitHub(
                    token = fresh.accessToken,
                    pairingSecret = secret,
                    login = fresh.login,
                    repository = fresh.repository,
                    branch = fresh.branch,
                    tokenExpiresAtMs = expiry,
                )
                readRun(run, runNumber, fresh.repository, fresh.branch, fresh.accessToken)
            }
            return
        }

        val stillFresh = saved.tokenExpiresAtMs?.let { it > nowMs() + 60_000L } == true
        if (!stillFresh) {
            fail(run, "Reconnect GitHub once to refresh read access safely.")
            return
        }
        readRun(run, runNumber, saved.repository, saved.branch, saved.token)
    }

    @Synchronized fun cancel() {
        ++generation
        cancelLocked()
    }

    private fun cancelLocked() {
        active?.cancel()
        active = null
    }

    private fun readRun(
        generationId: Long,
        runNumber: Long,
        repository: String,
        branch: String,
        token: String,
        page: Int = 1,
    ) {
        synchronized(this) {
            if (generationId != generation) return
        }
        listener.onEvent(
            WorkspaceWorkPhase.VERIFYING,
            "Checking GitHub Actions build",
            "#$runNumber · $branch · page $page",
        )
        dispatch(
            generationId,
            WorkspaceGitHubConnector.workflowRunsForBranchRequest(
                token = token,
                repository = repository,
                branch = branch,
                page = page,
            ),
            "GitHub Actions build list could not be read; no repository change was attempted.",
        ) { response ->
            val lookup = WorkspaceGitHubConnector.readWorkflowRunPageByNumber(
                response = response,
                expectedRunNumber = runNumber,
                expectedBranch = branch,
            )
            if (lookup.run == null) {
                if (lookup.fetchedCount == WorkspaceGitHubConnector.RUN_LOOKUP_PAGE_SIZE &&
                    page < WorkspaceGitHubConnector.RUN_LOOKUP_MAX_PAGES
                ) {
                    readRun(generationId, runNumber, repository, branch, token, page + 1)
                    return@dispatch
                }
                throw IllegalArgumentException(
                    "Build #$runNumber was not found among the latest 100 push runs on $branch."
                )
            }
            val found = lookup.run
            synchronized(this) {
                if (generationId != generation) return@dispatch
            }
            listener.onEvent(
                WorkspaceWorkPhase.DONE,
                "GitHub build verified",
                "#${found.runNumber} · ${found.status}" +
                    (found.conclusion?.let { " · $it" } ?: ""),
            )
            listener.onComplete(Completion(repository, branch, found))
        }
    }

    private fun dispatch(
        generationId: Long,
        request: Request,
        networkFailure: String,
        onResponse: (Response) -> Unit,
    ) {
        val call = WorkspaceGitHubConnector.client.newCall(request)
        synchronized(this) {
            if (generationId != generation) return
            active = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@WorkspaceConnectedGitHubRunRunner) {
                    if (generationId != generation || active !== call) return
                    active = null
                }
                fail(generationId, networkFailure)
            }

            override fun onResponse(call: Call, response: Response) {
                synchronized(this@WorkspaceConnectedGitHubRunRunner) {
                    if (generationId != generation || active !== call) {
                        response.close()
                        return
                    }
                    active = null
                }
                runCatching {
                    onResponse(response)
                }.onFailure {
                    response.close()
                    fail(
                        generationId,
                        it.message ?: "GitHub build verification stopped safely.",
                    )
                }
            }
        })
    }

    private fun fail(generationId: Long, message: String) {
        synchronized(this) {
            if (generationId != generation) return
            active = null
        }
        listener.onEvent(
            WorkspaceWorkPhase.ERROR,
            "GitHub build verification stopped",
            message,
        )
        listener.onError(message)
    }
}
