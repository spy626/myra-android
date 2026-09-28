package com.myra.assistant.ui.workspace

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
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
 * -> one broker-gated non-force commit -> exact-SHA GitHub Actions verification -> draft PR.
 *
 * A successful commit is not completion. DONE is emitted only after that exact push CI is GREEN.
 * main/master, merge, workflow writes,
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
        val workflow: WorkspaceGitHubConnector.WorkflowRun,
        val pullRequest: WorkspaceGitHubConnector.PullRequestReceipt?,
        val warning: String? = null,
    )

    interface Listener {
        fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String? = null)
        fun onComplete(result: Completion)
        fun onError(message: String)
    }

    private companion object {
        const val CI_POLL_MS = 8_000L
        const val MAX_CI_POLLS = 120
        const val MAX_CI_POLL_ERRORS = 3
        const val CP_SHA = "workspace_github_self_edit_checkpoint_sha"
        const val CP_REPO = "workspace_github_self_edit_checkpoint_repo"
        const val CP_BRANCH = "workspace_github_self_edit_checkpoint_branch"
        const val CP_PATH = "workspace_github_self_edit_checkpoint_path"
        const val CP_PROVIDER = "workspace_github_self_edit_checkpoint_provider"
        const val CP_PHASE = "workspace_github_self_edit_checkpoint_phase"
    }

    private val pollHandler = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var active: Call? = null
    private var ciWatching = false
    private var instruction = ""
    private var connection: WorkspaceConnectorCredentialStore.GitHubConnection? = null
    private var pairing = ""
    private var xKiroKey = ""
    private var groqKey = ""
    private var xKiroPermitted = false
    private var groqPermitted = false
    private var selectedProvider: WorkspaceCodingRoleRouter.Provider? = null
    private var repairAttempt = 0
    private var grant: WorkspaceGitHubConnector.InstallationGrant? = null
    private var access: WorkspaceGitHubConnector.WriteAccess? = null
    private var candidate: WorkspaceAgentReachGitHubRelevance.Candidate? = null
    private var originalSource = ""

    val isRunning: Boolean get() = synchronized(this) { active != null || ciWatching }

    @Synchronized fun cancel() {
        generation += 1
        active?.cancel()
        active = null
        pollHandler.removeCallbacksAndMessages(null)
        ciWatching = false
        // A post-commit checkpoint intentionally survives cancellation/app interruption.
        clearState()
    }

    @Synchronized fun start(message: String) {
        if (active != null || ciWatching) {
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
        val route = requireNotNull(selectedProvider)
        sendProvider(
            run = run,
            route = route,
            prompt = prompt,
            sourceForPatch = originalSource,
            expectedHead = requireNotNull(access).headSha,
            repair = false,
            fallbackUsed = false,
        )
    }

    private fun alternateProvider(
        current: WorkspaceCodingRoleRouter.Provider,
    ): WorkspaceCodingRoleRouter.Provider? = when (current) {
        WorkspaceCodingRoleRouter.Provider.XKIRO ->
            WorkspaceCodingRoleRouter.Provider.GROQ.takeIf {
                groqPermitted &&
                    WorkspaceProviderSessionHealth.canSend(WorkspaceProviderRegistry.Id.GROQ_FREE)
            }
        WorkspaceCodingRoleRouter.Provider.GROQ ->
            WorkspaceCodingRoleRouter.Provider.XKIRO.takeIf {
                xKiroPermitted &&
                    WorkspaceProviderSessionHealth.canSend(WorkspaceProviderRegistry.Id.XKIRO_FREE)
            }
    }

    private fun providerId(route: WorkspaceCodingRoleRouter.Provider) = when (route) {
        WorkspaceCodingRoleRouter.Provider.XKIRO -> WorkspaceProviderRegistry.Id.XKIRO_FREE
        WorkspaceCodingRoleRouter.Provider.GROQ -> WorkspaceProviderRegistry.Id.GROQ_FREE
    }

    private fun providerName(route: WorkspaceCodingRoleRouter.Provider) = when (route) {
        WorkspaceCodingRoleRouter.Provider.XKIRO -> "xKiro · ${WorkspaceXKiroFree.MODEL}"
        WorkspaceCodingRoleRouter.Provider.GROQ -> "Groq · ${WorkspaceGroqFree.MODEL}"
    }

    private fun sendProvider(
        run: Long,
        route: WorkspaceCodingRoleRouter.Provider,
        prompt: String,
        sourceForPatch: String,
        expectedHead: String,
        repair: Boolean,
        fallbackUsed: Boolean,
    ) {
        val selected = requireNotNull(candidate)
        val label = providerName(route)
        selectedProvider = route
        listener.onEvent(
            if (repair) WorkspaceWorkPhase.RECOVERING else WorkspaceWorkPhase.CODING,
            if (repair) "Repairing failed exact CI" else "Preparing bounded GitHub edit",
            "$label · ${selected.path}",
        )

        val providerRequest = runCatching {
            when (route) {
                WorkspaceCodingRoleRouter.Provider.XKIRO ->
                    WorkspaceXKiroFree.deliberationRequest(xKiroKey, prompt)
                WorkspaceCodingRoleRouter.Provider.GROQ ->
                    WorkspaceGroqFree.request(
                        groqKey,
                        listOf(
                            WorkspaceConversationStore.Message(
                                if (repair) "github-self-edit-repair" else "github-self-edit",
                                "user",
                                prompt,
                                nowMs(),
                            )
                        ),
                    )
            }
        }.getOrElse { error ->
            if (!fallbackUsed && error is WorkspaceXKiroFree.NoSourcePreflight) {
                val alternate = alternateProvider(route)
                if (alternate != null) {
                    listener.onEvent(
                        WorkspaceWorkPhase.RECOVERING,
                        "Switching coding provider before source send",
                        "${providerName(route)} → ${providerName(alternate)}",
                    )
                    sendProvider(run, alternate, prompt, sourceForPatch, expectedHead, repair, true)
                    return
                }
            }
            fail(run, null, error.message ?: "$label request could not be prepared.")
            return
        }

        val client = when (route) {
            WorkspaceCodingRoleRouter.Provider.XKIRO -> WorkspaceXKiroFree.deliberationClient
            WorkspaceCodingRoleRouter.Provider.GROQ -> WorkspaceFreeAiSuggestion.client
        }
        val call = client.newCall(providerRequest)
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
                if (!fallbackUsed && e is WorkspaceXKiroFree.NoSourcePreflight) {
                    val alternate = alternateProvider(route)
                    if (alternate != null) {
                        listener.onEvent(
                            WorkspaceWorkPhase.RECOVERING,
                            "Switching coding provider before source send",
                            "${providerName(route)} → ${providerName(alternate)}",
                        )
                        sendProvider(run, alternate, prompt, sourceForPatch, expectedHead, repair, true)
                        return
                    }
                }
                fail(
                    run,
                    null,
                    "$label request had an uncertain network outcome; no automatic cross-provider resend.",
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
                val id = providerId(route)
                WorkspaceProviderSessionHealth.recordResponse(response)
                if (!response.isSuccessful &&
                    !fallbackUsed &&
                    WorkspaceProviderRegistry.definitiveFallbackAllowed(id, response.code)
                ) {
                    val status = response.code
                    response.close()
                    val alternate = alternateProvider(route)
                    if (alternate != null) {
                        listener.onEvent(
                            WorkspaceWorkPhase.RECOVERING,
                            "Switching after definitive provider HTTP $status",
                            "${providerName(route)} → ${providerName(alternate)}",
                        )
                        sendProvider(run, alternate, prompt, sourceForPatch, expectedHead, repair, true)
                        return
                    }
                    fail(run, null, "$label HTTP $status; no permitted coding fallback available.")
                    return
                }
                val prepared = runCatching {
                    val raw = when (route) {
                        WorkspaceCodingRoleRouter.Provider.XKIRO ->
                            WorkspaceXKiroFree.readDeliberation(response)
                        WorkspaceCodingRoleRouter.Provider.GROQ ->
                            WorkspaceGroqFree.read(response)
                    }
                    WorkspaceGitHubSelfEdit.prepare(raw, selected.path, sourceForPatch)
                }.getOrElse { error ->
                    fail(run, null, error.message ?: "$label coding response was rejected.")
                    return
                }
                listener.onEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    if (repair) "Repair patch accepted locally" else "Provider patch accepted locally",
                    label,
                )
                commit(run, prepared, expectedHead, repair)
            }
        })
    }

    private fun commit(
        run: Long,
        prepared: WorkspaceGitHubSelfEdit.Prepared,
        expectedHead: String,
        repair: Boolean,
    ) {
        val checked = requireNotNull(access)
        val plan = WorkspaceGitHubWritePolicy.commitPlan(
            expectedHead = expectedHead,
            message = if (repair) WorkspaceGitHubSelfEdit.repairCommitMessage(prepared.path)
                else WorkspaceGitHubSelfEdit.commitMessage(prepared.path),
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
            require(receipt.previousHead == expectedHead &&
                receipt.branch == checked.branch &&
                receipt.files == listOf(prepared.path)) {
                "GitHub commit receipt did not match the authorized self-edit"
            }
            access = checked.copy(headSha = receipt.commitSha)
            saveCheckpoint(receipt, if (repair) "repair_waiting_ci" else "waiting_ci")
            awaitExactCi(run, receipt)
        }
    }

    private fun awaitExactCi(
        run: Long,
        receipt: WorkspaceGitHubConnector.CommitReceipt,
    ) {
        synchronized(this) {
            if (run != generation) return
            ciWatching = true
        }
        listener.onEvent(
            WorkspaceWorkPhase.VERIFYING,
            "Waiting for exact GitHub Actions result",
            receipt.commitSha.take(12),
        )
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.installationTokenRequest(pairing),
            "GitHub CI read-token refresh failed; commit checkpoint was preserved.",
        ) { response ->
            val fresh = WorkspaceGitHubConnector.readInstallationGrant(response)
            val saved = requireNotNull(connection)
            require(fresh.repository.equals(saved.repository, ignoreCase = true) &&
                fresh.branch == saved.branch) {
                "GitHub CI read binding changed; checkpoint preserved"
            }
            pollExactCi(run, receipt, fresh.accessToken, attempt = 0, errors = 0)
        }
    }

    private fun pollExactCi(
        run: Long,
        receipt: WorkspaceGitHubConnector.CommitReceipt,
        token: String,
        attempt: Int,
        errors: Int,
    ) {
        if (attempt >= MAX_CI_POLLS) {
            fail(run, null, "Timed out waiting for exact GitHub Actions SHA; checkpoint preserved.")
            return
        }
        val saved = requireNotNull(connection)
        val request = WorkspaceGitHubConnector.workflowRunsRequest(
            token = token,
            repository = saved.repository,
            branch = saved.branch,
            headSha = receipt.commitSha,
        )
        val call = WorkspaceGitHubConnector.client.newCall(request)
        synchronized(this) {
            if (run != generation || !ciWatching) return
            active = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@WorkspaceGitHubSelfEditFlow) {
                    if (run != generation || active !== call) return
                    active = null
                }
                if (errors < MAX_CI_POLL_ERRORS) {
                    scheduleExactCi(run, receipt, token, attempt + 1, errors + 1)
                } else {
                    fail(run, null,
                        "GitHub Actions polling failed repeatedly; commit checkpoint preserved.")
                }
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
                    WorkspaceGitHubConnector.readWorkflowRunForHead(response, receipt.commitSha)
                }
                val workflow = parsed.getOrElse {
                    fail(run, null, it.message ?: "Exact GitHub Actions response was rejected.")
                    return
                }
                if (workflow == null) {
                    listener.onEvent(
                        WorkspaceWorkPhase.VERIFYING,
                        "Waiting for exact Actions run",
                        receipt.commitSha.take(12),
                    )
                    scheduleExactCi(run, receipt, token, attempt + 1, 0)
                    return
                }
                if (workflow.status != "completed") {
                    listener.onEvent(
                        WorkspaceWorkPhase.VERIFYING,
                        "CI #${workflow.runNumber} ${workflow.status}",
                        receipt.commitSha.take(12),
                    )
                    scheduleExactCi(run, receipt, token, attempt + 1, 0)
                    return
                }
                if (workflow.conclusion == "success") {
                    saveCheckpoint(receipt, "ci_green")
                    listener.onEvent(
                        WorkspaceWorkPhase.VERIFYING,
                        "CI #${workflow.runNumber} GREEN",
                        receipt.commitSha.take(12),
                    )
                    ensureDraftPr(run, receipt, workflow)
                    return
                }
                readCiFailure(run, receipt, workflow, token)
            }
        })
    }

    private fun scheduleExactCi(
        run: Long,
        receipt: WorkspaceGitHubConnector.CommitReceipt,
        token: String,
        attempt: Int,
        errors: Int,
    ) {
        pollHandler.postDelayed({
            val alive = synchronized(this) { run == generation && ciWatching }
            if (alive) pollExactCi(run, receipt, token, attempt, errors)
        }, CI_POLL_MS)
    }

    private fun readCiFailure(
        run: Long,
        receipt: WorkspaceGitHubConnector.CommitReceipt,
        workflow: WorkspaceGitHubConnector.WorkflowRun,
        token: String,
    ) {
        val saved = requireNotNull(connection)
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.workflowRunJobsRequest(
                token = token,
                repository = saved.repository,
                runId = workflow.id,
            ),
            "CI failed and bounded failure details could not be read; checkpoint preserved.",
        ) { response ->
            val failure = WorkspaceGitHubConnector.readWorkflowFailure(response, workflow)
            val summary = failure.boundedSummary()
            saveCheckpoint(receipt, "ci_failed")
            if (repairAttempt >= 1) {
                fail(
                    run,
                    null,
                    "CI #${workflow.runNumber} failed after one repair for " +
                        receipt.commitSha.take(12) + ". " + summary +
                        ". Task is not done; checkpoint preserved.",
                )
                return@dispatch
            }
            repairAttempt = 1
            ciWatching = false
            listener.onEvent(
                WorkspaceWorkPhase.RECOVERING,
                "Reading failed commit for one bounded repair",
                "CI #${workflow.runNumber} · ${receipt.commitSha.take(12)}",
            )
            dispatch(
                run,
                WorkspaceGitHubConnector.client,
                WorkspaceGitHubConnector.fileContentRequest(
                    token = token,
                    repository = saved.repository,
                    headSha = receipt.commitSha,
                    path = receipt.files.single(),
                ),
                "Failed commit source could not be read; checkpoint preserved.",
            ) { fileResponse ->
                val currentSource = WorkspaceGitHubConnector.readTextFile(
                    fileResponse,
                    receipt.files.single(),
                )
                val prompt = WorkspaceGitHubSelfEdit.repairPrompt(
                    message = instruction,
                    path = receipt.files.single(),
                    content = currentSource,
                    ciFailureSummary = summary,
                )
                sendProvider(
                    run = run,
                    route = requireNotNull(selectedProvider),
                    prompt = prompt,
                    sourceForPatch = currentSource,
                    expectedHead = receipt.commitSha,
                    repair = true,
                    fallbackUsed = false,
                )
            }
        }
    }

    private fun ensureDraftPr(
        run: Long,
        receipt: WorkspaceGitHubConnector.CommitReceipt,
        workflow: WorkspaceGitHubConnector.WorkflowRun,
    ) {
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
                        workflow = workflow,
                        pullRequest = null,
                        warning = "CI passed, but the draft PR update was not confirmed.",
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
                            workflow = workflow,
                            pullRequest = null,
                            warning = "CI passed, but the draft PR update was refused: " +
                                (it.message ?: "unknown GitHub response"),
                        ),
                    )
                    return
                }
                complete(run, Completion(receipt, workflow, pr))
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
            ciWatching = false
            pollHandler.removeCallbacksAndMessages(null)
            clearCheckpoint()
            clearState()
        }
        listener.onEvent(
            WorkspaceWorkPhase.DONE,
            "GitHub self-edit CI verified",
            "CI #${result.workflow.runNumber} · ${result.commit.commitSha.take(12)}",
        )
        listener.onComplete(result)
    }

    private fun fail(run: Long, call: Call?, message: String) {
        synchronized(this) {
            if (run != generation || (call != null && active !== call)) return
            active = null
            ciWatching = false
            pollHandler.removeCallbacksAndMessages(null)
            clearState()
        }
        listener.onError(message)
    }

    private fun saveCheckpoint(
        receipt: WorkspaceGitHubConnector.CommitReceipt,
        phase: String,
    ) {
        val provider = selectedProvider?.name ?: "UNKNOWN"
        preferences.edit()
            .putString(CP_SHA, receipt.commitSha)
            .putString(CP_REPO, receipt.repository)
            .putString(CP_BRANCH, receipt.branch)
            .putString(CP_PATH, receipt.files.singleOrNull().orEmpty())
            .putString(CP_PROVIDER, provider)
            .putString(CP_PHASE, phase)
            .apply()
    }

    private fun clearCheckpoint() {
        preferences.edit()
            .remove(CP_SHA)
            .remove(CP_REPO)
            .remove(CP_BRANCH)
            .remove(CP_PATH)
            .remove(CP_PROVIDER)
            .remove(CP_PHASE)
            .apply()
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
        repairAttempt = 0
        grant = null
        access = null
        candidate = null
        originalSource = ""
    }
}
