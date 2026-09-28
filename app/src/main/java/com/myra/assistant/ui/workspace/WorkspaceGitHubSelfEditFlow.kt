package com.myra.assistant.ui.workspace

import android.content.SharedPreferences
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
    private val preferences: SharedPreferences,
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
    private var xKiroKey = ""
    private var groqKey = ""
    private var xKiroPermitted = false
    private var groqPermitted = false
    private var selectedProvider: WorkspaceCodingRoleRouter.Provider? = null
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
        val xKiroEnabled = preferences.getBoolean(WorkspaceXKiroFree.PREFERENCE_KEY, false)
        val fallbackEnabled = preferences.getBoolean(WorkspaceCodingAutoFallback.PREFERENCE_KEY, false)
        val groqFreeZdr = preferences.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false)
        val savedXKiroKey = runCatching { keys.get(ApiKeyStore.XKIRO) }.getOrDefault("")
        val savedGroqKey = runCatching { keys.get(ApiKeyStore.GROQ) }.getOrDefault("")
        val canUseXKiro = xKiroEnabled && WorkspaceXKiroFree.validKey(savedXKiroKey)
        val canShareWithGroq = WorkspaceCodingAutoFallback.permitted(fallbackEnabled, xKiroEnabled) &&
            WorkspaceCodingAutoFallback.groqPermitted(fallbackEnabled, groqFreeZdr, savedGroqKey)
        if (!canUseXKiro && !canShareWithGroq) {
            listener.onError("Enable xKiro Work coding with a valid Free key. Groq small-edit routing additionally requires the existing automatic Free-provider sharing permission and Groq Free/ZDR.")
            return
        }

        val run = ++generation
        instruction = message
        connection = saved
        pairing = secret
        xKiroKey = savedXKiroKey
        groqKey = savedGroqKey
        xKiroPermitted = canUseXKiro
        groqPermitted = canShareWithGroq
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
            selectedProvider = requireNotNull(
                WorkspaceCodingRoleRouter.select(
                    instruction,
                    source.length,
                    xKiroPermitted && WorkspaceProviderSessionHealth.canSend(WorkspaceProviderRegistry.Id.XKIRO_FREE),
                    groqPermitted && WorkspaceProviderSessionHealth.canSend(WorkspaceProviderRegistry.Id.GROQ_FREE),
                )
            ) { "All permitted coding providers are cooling down or unavailable; no source was sent" }
            val prompt = WorkspaceGitHubSelfEdit.prompt(instruction, selected.path, source)
            propose(run, prompt)
        }
    }

    private fun propose(run: Long, prompt: String) {
        val selected = requireNotNull(candidate)
        val route = requireNotNull(selectedProvider)
        val providerName = when (route) {
            WorkspaceCodingRoleRouter.Provider.XKIRO -> "xKiro · ${WorkspaceXKiroFree.MODEL}"
            WorkspaceCodingRoleRouter.Provider.GROQ -> "Groq · ${WorkspaceGroqFree.MODEL}"
        }
        listener.onEvent(WorkspaceWorkPhase.CODING, "Preparing bounded GitHub edit", "$providerName · ${selected.path}")
        val providerRequest = when (route) {
            WorkspaceCodingRoleRouter.Provider.XKIRO -> WorkspaceXKiroFree.deliberationRequest(xKiroKey, prompt)
            WorkspaceCodingRoleRouter.Provider.GROQ -> WorkspaceGroqFree.request(
                groqKey,
                listOf(WorkspaceConversationStore.Message("github-self-edit", "user", prompt, nowMs())),
            )
        }
        val client = when (route) {
            WorkspaceCodingRoleRouter.Provider.XKIRO -> WorkspaceXKiroFree.deliberationClient
            WorkspaceCodingRoleRouter.Provider.GROQ -> WorkspaceFreeAiSuggestion.client
        }
        dispatch(run, client, providerRequest, "$providerName self-edit request failed; no GitHub write was attempted.") { response ->
            WorkspaceProviderSessionHealth.recordResponse(response)
            val raw = when (route) {
                WorkspaceCodingRoleRouter.Provider.XKIRO -> WorkspaceXKiroFree.readDeliberation(response)
                WorkspaceCodingRoleRouter.Provider.GROQ -> WorkspaceGroqFree.read(response)
            }
            val prepared = WorkspaceGitHubSelfEdit.prepare(raw, selected.path, originalSource)
            listener.onEvent(WorkspaceWorkPhase.VERIFYING, "Provider patch accepted locally", providerName)
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
        xKiroKey = ""
        groqKey = ""
        xKiroPermitted = false
        groqPermitted = false
        selectedProvider = null
        grant = null
        access = null
        candidate = null
        originalSource = ""
    }
}
