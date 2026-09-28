package com.myra.assistant.ui.workspace

import com.myra.assistant.ai.ApiKeyStore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * One current-turn GitHub self-edit transaction:
 * fresh read token -> automatic write preflight -> pinned feature-branch read -> one provider patch
 * -> one broker-gated non-force commit -> ensure draft PR.
 *
 * There is no manual verification button and no retry. main/master, merge, workflow writes,
 * destructive deletes and secrets remain outside this capability.
 */
internal class WorkspaceGitHubSelfEditFlow(
    private val store: WorkspaceConnectorCredentialStore,
    private val keys: ApiKeyStore,
    private val listener: Listener,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class Completion(
        val commit: WorkspaceGitHubConnector.CommitReceipt,
        val pullRequest: WorkspaceGitHubConnector.PullRequestReceipt?,
        val warning: String? = null,
    )

    interface Listener {
        fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String? = null)
        fun onComplete(result: Completion)
        fun onError(message: String)
    }

    private var generation = 0L
    private var active: Call? = null
    private var instruction = ""
    private var connection: WorkspaceConnectorCredentialStore.GitHubConnection? = null
    private var pairing = ""
    private var openRouterKey = ""
    private var grant: WorkspaceGitHubConnector.InstallationGrant? = null
    private var access: WorkspaceGitHubConnector.WriteAccess? = null
    private var candidate: WorkspaceAgentReachGitHubRelevance.Candidate? = null
    private var originalSource = ""

    val isRunning: Boolean get() = synchronized(this) { active != null }

    @Synchronized fun cancel() {
        generation += 1
        active?.cancel()
        active = null
        clearState()
    }

    @Synchronized fun start(message: String) {
        if (active != null) {
            listener.onError("A GitHub self-edit is already running.")
            return
        }
        if (!WorkspaceGitHubSelfEdit.isExplicitRequest(message)) {
            listener.onError("This turn does not explicitly authorize a connected-repository code change.")
            return
        }
        val saved = store.loadGitHub()
        if (saved == null) {
            listener.onError("Connect the LYRA GitHub App first. No repository write was attempted.")
            return
        }
        val secret = saved.pairingSecret
        if (secret == null) {
            listener.onError("Reconnect GitHub once to restore the encrypted pairing key.")
            return
        }
        val key = runCatching { keys.get(ApiKeyStore.OPENROUTER) }.getOrDefault("")
        if (key.isBlank()) {
            listener.onError("Add one OpenRouter Free API key in API & Cloud Settings before GitHub self-edit. No source was sent.")
            return
        }
        WorkspaceProviderSessionHealth.cooldownMessage(
            WorkspaceProviderRegistry.Id.OPENROUTER_FREE
        ).takeIf { it.isNotBlank() }?.let {
            listener.onError(it + " No GitHub source was sent.")
            return
        }

        val run = ++generation
        instruction = message
        connection = saved
        pairing = secret
        openRouterKey = key
        listener.onEvent(WorkspaceWorkPhase.READING, "Refreshing GitHub read access", saved.branch)
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.installationTokenRequest(secret),
            "GitHub read-token refresh failed; no write was attempted.",
        ) { response ->
            val fresh = WorkspaceGitHubConnector.readInstallationGrant(response)
            require(fresh.repository.equals(saved.repository, ignoreCase = true) &&
                fresh.branch == saved.branch &&
                fresh.login.equals(saved.login, ignoreCase = true)) {
                "Fresh GitHub installation binding changed; self-edit stopped"
            }
            val expiry = Math.addExact(nowMs(), Math.multiplyExact(fresh.expiresInSeconds, 1_000L))
            store.saveGitHub(
                token = fresh.accessToken,
                pairingSecret = secret,
                login = fresh.login,
                repository = fresh.repository,
                branch = fresh.branch,
                tokenExpiresAtMs = expiry,
            )
            grant = fresh
            verifyWrite(run)
        }
    }

    private fun verifyWrite(run: Long) {
        listener.onEvent(WorkspaceWorkPhase.VERIFYING, "Checking protected write lane", "Automatic preflight")
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.writeAccessRequest(pairing),
            "GitHub write preflight failed; no repository change was made.",
        ) { response ->
            val checked = WorkspaceGitHubConnector.readWriteAccess(response)
            val saved = requireNotNull(connection)
            require(checked.repository.equals(saved.repository, ignoreCase = true) &&
                checked.branch == saved.branch && checked.prBase == "main") {
                "GitHub write binding did not match LYRA's protected self-edit policy"
            }
            access = checked
            readPathMap(run)
        }
    }

    private fun readPathMap(run: Long) {
        val saved = requireNotNull(connection)
        val fresh = requireNotNull(grant)
        val checked = requireNotNull(access)
        listener.onEvent(WorkspaceWorkPhase.READING, "Mapping connected repository", checked.headSha.take(12))
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.pathMapRequest(
                fresh.accessToken, saved.repository, checked.headSha),
            "GitHub repository map could not be read; no write was attempted.",
        ) { response ->
            val map = WorkspaceGitHubConnector.readPathMap(response, checked.headSha)
            candidate = WorkspaceGitHubSelfEdit.selectCandidate(instruction, map)
            readCandidate(run)
        }
    }

    private fun readCandidate(run: Long) {
        val saved = requireNotNull(connection)
        val fresh = requireNotNull(grant)
        val checked = requireNotNull(access)
        val selected = requireNotNull(candidate)
        listener.onEvent(WorkspaceWorkPhase.READING, "Reading one target file", selected.path)
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.fileContentRequest(
                fresh.accessToken, saved.repository, checked.headSha, selected.path),
            "GitHub target file could not be read; no write was attempted.",
        ) { response ->
            val source = WorkspaceGitHubConnector.readTextFile(response, selected.path)
            originalSource = source
            val prompt = WorkspaceGitHubSelfEdit.prompt(instruction, selected.path, source)
            propose(run, prompt)
        }
    }

    private fun propose(run: Long, prompt: String) {
        val selected = requireNotNull(candidate)
        listener.onEvent(WorkspaceWorkPhase.CODING, "Preparing bounded GitHub edit", selected.path)
        dispatch(
            run,
            WorkspaceFreeAiSuggestion.client,
            WorkspaceFreeAiSuggestion.request(openRouterKey, prompt),
            "OpenRouter Free self-edit request failed; no GitHub write was attempted.",
        ) { response ->
            WorkspaceProviderSessionHealth.recordResponse(response)
            val raw = WorkspaceFreeAiSuggestion.readResponse(response)
            val prepared = WorkspaceGitHubSelfEdit.prepare(raw, selected.path, originalSource)
            commit(run, prepared)
        }
    }

    private fun commit(run: Long, prepared: WorkspaceGitHubSelfEdit.Prepared) {
        val checked = requireNotNull(access)
        val plan = WorkspaceGitHubWritePolicy.commitPlan(
            expectedHead = checked.headSha,
            message = WorkspaceGitHubSelfEdit.commitMessage(prepared.path),
            files = listOf(WorkspaceGitHubWritePolicy.FileChange(prepared.path, prepared.content)),
        )
        listener.onEvent(WorkspaceWorkPhase.CODING, "Committing protected feature-branch edit", prepared.path)
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.commitRequest(pairing, plan),
            "GitHub commit was not confirmed. Do not retry automatically.",
        ) { response ->
            val receipt = WorkspaceGitHubConnector.readCommitReceipt(response)
            require(receipt.previousHead == checked.headSha &&
                receipt.branch == checked.branch &&
                receipt.files == listOf(prepared.path)) {
                "GitHub commit receipt did not match the authorized self-edit"
            }
            ensureDraftPr(run, receipt)
        }
    }

    private fun ensureDraftPr(run: Long, receipt: WorkspaceGitHubConnector.CommitReceipt) {
        listener.onEvent(WorkspaceWorkPhase.VERIFYING, "Updating draft PR", "No merge")
        val call = WorkspaceGitHubConnector.client.newCall(
            WorkspaceGitHubConnector.ensureDraftPullRequestRequest(pairing)
        )
        synchronized(this) {
            if (run != generation) return
            active = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@WorkspaceGitHubSelfEditFlow) {
                    if (run != generation || active !== call) return
                    active = null
                }
                complete(
                    run,
                    Completion(
                        commit = receipt,
                        pullRequest = null,
                        warning = "The feature-branch commit succeeded, but the draft PR update was not confirmed.",
                    ),
                )
            }

            override fun onResponse(call: Call, response: Response) {
                synchronized(this@WorkspaceGitHubSelfEditFlow) {
                    if (run != generation || active !== call) {
                        response.close()
                        return
                    }
                    active = null
                }
                val parsed = runCatching {
                    val pr = WorkspaceGitHubConnector.readPullRequestReceipt(response)
                    require(pr.draft && pr.head == receipt.branch && pr.base == "main") {
                        "Draft PR receipt did not match the protected branch"
                    }
                    pr
                }
                val pr = parsed.getOrElse {
                    complete(
                        run,
                        Completion(
                            commit = receipt,
                            pullRequest = null,
                            warning = "The feature-branch commit succeeded, but the draft PR update was refused: " +
                                (it.message ?: "unknown GitHub response"),
                        ),
                    )
                    return
                }
                complete(run, Completion(receipt, pr))
            }
        })
    }

    private fun dispatch(
        run: Long,
        client: OkHttpClient,
        request: Request,
        networkMessage: String,
        accept: (Response) -> Unit,
    ) {
        val call = client.newCall(request)
        synchronized(this) {
            if (run != generation) return
            active = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fail(run, call, networkMessage)
            }

            override fun onResponse(call: Call, response: Response) {
                synchronized(this@WorkspaceGitHubSelfEditFlow) {
                    if (run != generation || active !== call) {
                        response.close()
                        return
                    }
                    active = null
                }
                runCatching { accept(response) }
                    .onFailure { error ->
                        runCatching { response.close() }
                        fail(run, null, error.message ?: "GitHub self-edit stopped safely.")
                    }
            }
        })
    }

    private fun complete(run: Long, result: Completion) {
        synchronized(this) {
            if (run != generation) return
            active = null
            clearState()
        }
        listener.onEvent(
            WorkspaceWorkPhase.DONE,
            "GitHub self-edit committed",
            result.commit.commitSha.take(12),
        )
        listener.onComplete(result)
    }

    private fun fail(run: Long, call: Call?, message: String) {
        synchronized(this) {
            if (run != generation || (call != null && active !== call)) return
            active = null
            clearState()
        }
        listener.onError(message)
    }

    private fun clearState() {
        instruction = ""
        connection = null
        pairing = ""
        openRouterKey = ""
        grant = null
        access = null
        candidate = null
        originalSource = ""
    }
}
