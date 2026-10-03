package com.myra.assistant.ui.workspace

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Fresh, sequential GET-only access to the configured feature branch and optionally main.
 * The only non-GET request is the existing broker read-token refresh. No write-access check,
 * commit endpoint, build dispatch, source editing, provider or retry is reachable here.
 */
internal class WorkspaceConnectedGitHubHeadsRunner(
    private val store: WorkspaceConnectorCredentialStore,
    private val listener: Listener,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class Completion(
        val repository: String,
        val branches: List<WorkspaceGitHubConnector.Branch>,
    )

    interface Listener {
        fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String? = null)
        fun onComplete(completion: Completion)
        fun onError(message: String)
    }

    private var generation = 0L
    private var active: Call? = null

    @Synchronized fun start(decision: WorkspaceConnectedGitHubHeadsIntent.Decision) {
        cancelLocked()
        val run = ++generation
        val saved = store.loadGitHub()
        if (saved == null) {
            fail(run, "Connect the LYRA GitHub App first. No repository change was attempted.")
            return
        }
        if (decision.namedFeatureBranch != null &&
            decision.namedFeatureBranch != saved.branch) {
            fail(run, "Requested branch is not the paired feature branch. No other branch was read.")
            return
        }
        if (decision.namedRepository != null &&
            !decision.namedRepository.equals(saved.repository, ignoreCase = true)) {
            fail(run, "Requested repository does not match the paired GitHub repository.")
            return
        }
        val wanted = buildList {
            if (decision.feature) add(saved.branch)
            if (decision.main) add("main")
        }.distinct()

        val secret = saved.pairingSecret
        if (secret != null) {
            listener.onEvent(WorkspaceWorkPhase.READING, "Refreshing GitHub read access", null)
            dispatch(
                run, WorkspaceGitHubConnector.installationTokenRequest(secret),
                "GitHub read-token refresh failed; no repository change was attempted.",
            ) { response ->
                val fresh = WorkspaceGitHubConnector.readInstallationGrant(response)
                require(fresh.repository.equals(saved.repository, ignoreCase = true) &&
                    fresh.branch == saved.branch &&
                    fresh.login.equals(saved.login, ignoreCase = true)) {
                    "Fresh installation identity changed; read stopped safely"
                }
                val expiry = Math.addExact(nowMs(),
                    Math.multiplyExact(fresh.expiresInSeconds, 1_000L))
                store.saveGitHub(
                    token = fresh.accessToken,
                    pairingSecret = secret,
                    login = fresh.login,
                    repository = fresh.repository,
                    branch = fresh.branch,
                    tokenExpiresAtMs = expiry,
                )
                readBranch(run, fresh.repository, fresh.accessToken, wanted, emptyList())
            }
            return
        }
        if (saved.tokenExpiresAtMs?.let { it > nowMs() + 60_000L } != true) {
            fail(run, "Reconnect GitHub once to refresh read access safely.")
            return
        }
        readBranch(run, saved.repository, saved.token, wanted, emptyList())
    }

    private fun readBranch(
        run: Long,
        repository: String,
        token: String,
        remaining: List<String>,
        verified: List<WorkspaceGitHubConnector.Branch>,
    ) {
        if (remaining.isEmpty()) {
            synchronized(this) { if (run != generation) return }
            listener.onEvent(WorkspaceWorkPhase.DONE, "Live GitHub HEADs verified",
                verified.joinToString(" · ") { it.name })
            listener.onComplete(Completion(repository, verified))
            return
        }
        val branch = WorkspaceConnectorPolicy.requireReadBranch(remaining.first())
        listener.onEvent(WorkspaceWorkPhase.VERIFYING, "Reading LIVE GitHub branch HEAD", branch)
        dispatch(
            run,
            WorkspaceGitHubConnector.branchRequest(token, repository, branch),
            "Live HEAD for $branch could not be read; no commit or build was started.",
        ) { response ->
            val result = WorkspaceGitHubConnector.readBranch(response, branch)
            readBranch(run, repository, token, remaining.drop(1), verified + result)
        }
    }

    @Synchronized fun cancel() {
        ++generation
        cancelLocked()
    }

    private fun cancelLocked() {
        active?.cancel()
        active = null
    }

    private fun dispatch(
        run: Long,
        request: Request,
        networkFailure: String,
        consume: (Response) -> Unit,
    ) {
        val call = WorkspaceGitHubConnector.client.newCall(request)
        synchronized(this) {
            if (run != generation) return
            active = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@WorkspaceConnectedGitHubHeadsRunner) {
                    if (run != generation || active !== call) return
                    active = null
                }
                fail(run, networkFailure)
            }

            override fun onResponse(call: Call, response: Response) {
                synchronized(this@WorkspaceConnectedGitHubHeadsRunner) {
                    if (run != generation || active !== call) {
                        response.close()
                        return
                    }
                    active = null
                }
                runCatching {
                    response.use(consume)
                }.onFailure {
                    fail(run, it.message ?: "Live GitHub HEAD read stopped safely.")
                }
            }
        })
    }

    private fun fail(run: Long, message: String) {
        synchronized(this) {
            if (run != generation) return
            active = null
        }
        listener.onEvent(WorkspaceWorkPhase.ERROR, "GitHub HEAD read stopped", message)
        listener.onError(message)
    }
}
