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
import java.util.Locale

/**
 * One current-turn GitHub self-edit transaction:
 * fresh read token -> automatic write preflight -> pinned feature-branch read -> one provider patch
 * -> optional read-only second-provider QA -> one broker-gated non-force commit
 * -> exact-SHA GitHub Actions verification -> draft PR.
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
        const val CP_PREVIOUS_HEAD = "workspace_github_self_edit_checkpoint_previous_head"
        const val CP_REPO = "workspace_github_self_edit_checkpoint_repo"
        const val CP_BRANCH = "workspace_github_self_edit_checkpoint_branch"
        const val CP_PATH = "workspace_github_self_edit_checkpoint_path"
        const val CP_PATHS = "workspace_github_self_edit_checkpoint_paths"
        const val CP_PROVIDER = "workspace_github_self_edit_checkpoint_provider"
        const val CP_PHASE = "workspace_github_self_edit_checkpoint_phase"
        const val CP_INSTRUCTION = "workspace_github_self_edit_checkpoint_instruction"
        const val CP_REPAIR_ATTEMPT = "workspace_github_self_edit_checkpoint_repair_attempt"
        const val CP_REVIEW_REVISION_ATTEMPT =
            "workspace_github_self_edit_checkpoint_review_revision_attempt"
        const val CP_BUDGET_PROVIDER_CALLS = "workspace_github_self_edit_budget_provider_calls"
        const val CP_BUDGET_REVIEW_CALLS = "workspace_github_self_edit_budget_review_calls"
        const val CP_BUDGET_FALLBACKS = "workspace_github_self_edit_budget_fallbacks"
        const val CP_BUDGET_CI_REPAIRS = "workspace_github_self_edit_budget_ci_repairs"
        const val CP_BUDGET_COMMITS = "workspace_github_self_edit_budget_commits"
        const val CP_LAST_FAILURE = "workspace_github_self_edit_last_failure"
        val CHECKPOINT_SHA = Regex("[0-9a-f]{40,64}")
        val CHECKPOINT_PHASES = setOf(
            "pre_commit", "waiting_ci", "repair_waiting_ci", "ci_failed", "ci_green"
        )
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
    private var reviewRevisionAttempt = 0
    private var taskBudget = WorkspaceGitHubTaskBudget.State()
    private var grant: WorkspaceGitHubConnector.InstallationGrant? = null
    private var access: WorkspaceGitHubConnector.WriteAccess? = null
    private var candidates: List<WorkspaceAgentReachGitHubRelevance.Candidate> = emptyList()
    private val originalSources = linkedMapOf<String, String>()
    private var codingPlan: WorkspaceGitHubCodingPlan.Plan? = null

    private data class Checkpoint(
        val receipt: WorkspaceGitHubConnector.CommitReceipt,
        val provider: WorkspaceCodingRoleRouter.Provider,
        val phase: String,
        val instruction: String,
        val repairAttempt: Int,
        val reviewRevisionAttempt: Int,
        val taskBudget: WorkspaceGitHubTaskBudget.State,
    )

    val isRunning: Boolean get() = synchronized(this) { active != null || ciWatching }

    fun hasCheckpoint(): Boolean =
        preferences.getString(CP_SHA, null)?.let(CHECKPOINT_SHA::matches) == true

    @Synchronized fun cancel(preserveCheckpoint: Boolean = true) {
        generation += 1
        active?.cancel()
        active = null
        pollHandler.removeCallbacksAndMessages(null)
        ciWatching = false
        if (!preserveCheckpoint) clearCheckpoint()
        // Lifecycle interruption preserves the current coding checkpoint; explicit Stop may discard it.
        clearState()
    }

    @Synchronized fun resumeCheckpoint(): Boolean {
        if (active != null || ciWatching) return false
        val checkpoint = runCatching { loadCheckpoint() }.getOrElse {
            clearCheckpoint()
            listener.onError("Saved GitHub coding checkpoint was invalid and was cleared safely.")
            return false
        } ?: return false
        val saved = store.loadGitHub()
        val secret = saved?.pairingSecret
        if (saved == null || secret == null) {
            listener.onError("Reconnect the LYRA GitHub App to resume the saved coding checkpoint.")
            return false
        }
        if (!saved.repository.equals(checkpoint.receipt.repository, ignoreCase = true) ||
            saved.branch != checkpoint.receipt.branch) {
            listener.onError("Saved coding checkpoint belongs to a different GitHub binding; no write was attempted.")
            return false
        }

        val xKiroEnabled = preferences.getBoolean(WorkspaceXKiroFree.PREFERENCE_KEY, false)
        val fallbackEnabled = preferences.getBoolean(WorkspaceCodingAutoFallback.PREFERENCE_KEY, false)
        val groqFreeZdr = preferences.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false)
        val savedXKiroKey = runCatching { keys.get(ApiKeyStore.XKIRO) }.getOrDefault("")
        val savedGroqKey = runCatching { keys.get(ApiKeyStore.GROQ) }.getOrDefault("")

        val run = ++generation
        instruction = checkpoint.instruction
        connection = saved
        pairing = secret
        xKiroKey = savedXKiroKey
        groqKey = savedGroqKey
        xKiroPermitted = xKiroEnabled && WorkspaceXKiroFree.validKey(savedXKiroKey)
        groqPermitted = WorkspaceCodingAutoFallback.permitted(fallbackEnabled, xKiroEnabled) &&
            WorkspaceCodingAutoFallback.groqPermitted(fallbackEnabled, groqFreeZdr, savedGroqKey)
        selectedProvider = checkpoint.provider
        repairAttempt = checkpoint.repairAttempt
        val restartInterruptedReviewCycle =
            checkpoint.phase == "pre_commit" && checkpoint.reviewRevisionAttempt > 0
        reviewRevisionAttempt =
            if (restartInterruptedReviewCycle) 0 else checkpoint.reviewRevisionAttempt
        if (restartInterruptedReviewCycle) {
            preferences.edit().putInt(CP_REVIEW_REVISION_ATTEMPT, 0).apply()
        }
        taskBudget = checkpoint.taskBudget
        codingPlan = WorkspaceGitHubCodingPlan.create(
            goal = checkpoint.instruction,
            repository = checkpoint.receipt.repository,
            branch = checkpoint.receipt.branch,
            selectedPaths = checkpoint.receipt.files,
        )
        listener.onEvent(
            WorkspaceWorkPhase.RECOVERING,
            "Resuming saved GitHub coding checkpoint",
            checkpoint.phase + " · " + checkpoint.receipt.commitSha.take(12),
        )
        if (restartInterruptedReviewCycle) {
            listener.onEvent(
                WorkspaceWorkPhase.RECOVERING,
                "Restarting interrupted pre-commit review cycle",
                "Reviewer lineage is rebuilt from freshly pinned source; no stale second review is assumed",
            )
        }
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.installationTokenRequest(secret),
            "GitHub read-token refresh failed; saved checkpoint was preserved.",
        ) { response ->
            val fresh = WorkspaceGitHubConnector.readInstallationGrant(response)
            require(fresh.repository.equals(saved.repository, ignoreCase = true) &&
                fresh.branch == saved.branch &&
                fresh.login.equals(saved.login, ignoreCase = true)) {
                "Fresh GitHub binding changed; saved checkpoint preserved"
            }
            grant = fresh
            dispatch(
                run,
                WorkspaceGitHubConnector.client,
                WorkspaceGitHubConnector.writeAccessRequest(pairing),
                "GitHub write preflight failed; saved checkpoint was preserved.",
            ) { writeResponse ->
                val checked = WorkspaceGitHubConnector.readWriteAccess(writeResponse)
                require(checked.repository.equals(saved.repository, ignoreCase = true) &&
                    checked.branch == saved.branch && checked.prBase == "main") {
                    "GitHub write binding changed; saved checkpoint preserved"
                }
                require(checked.headSha == checkpoint.receipt.commitSha) {
                    "Feature branch advanced beyond the saved checkpoint; refusing a blind resume."
                }
                access = checked
                if (checkpoint.phase == "pre_commit") {
                    resumePreCommit(run, checkpoint, fresh.accessToken)
                } else {
                    awaitExactCi(run, checkpoint.receipt)
                }
            }
        }
        return true
    }

    private fun resumePreCommit(
        run: Long,
        checkpoint: Checkpoint,
        token: String,
    ) {
        val storedRoute = checkpoint.provider
        val storedAvailable = when (storedRoute) {
            WorkspaceCodingRoleRouter.Provider.XKIRO ->
                xKiroPermitted &&
                    WorkspaceProviderSessionHealth.canSend(WorkspaceProviderRegistry.Id.XKIRO_FREE)
            WorkspaceCodingRoleRouter.Provider.GROQ ->
                groqPermitted &&
                    WorkspaceProviderSessionHealth.canSend(WorkspaceProviderRegistry.Id.GROQ_FREE)
        }
        val route = if (storedAvailable) storedRoute else
            alternateProvider(storedRoute)
                ?: throw IllegalStateException(
                    "Saved coding provider is unavailable and no permitted fallback is ready."
                )
        selectedProvider = route
        listener.onEvent(
            WorkspaceWorkPhase.RECOVERING,
            "Re-reading pinned sources before provider resume",
            checkpoint.receipt.files.size.toString() + " file(s) · " +
                checkpoint.receipt.commitSha.take(12),
        )
        readFilesAtSha(
            run = run,
            token = token,
            headSha = checkpoint.receipt.commitSha,
            paths = checkpoint.receipt.files,
        ) { sources ->
            originalSources.clear()
            originalSources.putAll(sources)
            val prompt = WorkspaceGitHubSelfEditBatch.prompt(instruction, sources)
            sendProvider(
                run = run,
                route = route,
                prompt = prompt,
                sourcesForPatch = sources,
                expectedHead = checkpoint.receipt.commitSha,
                repair = false,
                fallbackUsed = route != storedRoute,
            )
        }
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
        taskBudget = WorkspaceGitHubTaskBudget.State()
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
            candidates = WorkspaceGitHubSelfEditBatch.selectCandidates(instruction, map)
            codingPlan = WorkspaceGitHubCodingPlan.create(
                goal = instruction,
                repository = saved.repository,
                branch = saved.branch,
                selectedPaths = candidates.map { it.path },
            )
            listener.onEvent(
                WorkspaceWorkPhase.THINKING,
                "Execution plan locked",
                requireNotNull(codingPlan).traceSummary(),
            )
            readSelectedFiles(run)
        }
    }

    private fun readSelectedFiles(run: Long) {
        val fresh = requireNotNull(grant)
        val checked = requireNotNull(access)
        val paths = candidates.map { it.path }
        listener.onEvent(
            WorkspaceWorkPhase.READING,
            "Reading bounded related source set",
            paths.size.toString() + " file(s)",
        )
        readFilesAtSha(run, fresh.accessToken, checked.headSha, paths) { sources ->
            originalSources.clear()
            originalSources.putAll(sources)
            val totalChars = sources.values.sumOf(String::length)
            selectedProvider = requireNotNull(
                WorkspaceCodingRoleRouter.select(
                    instruction,
                    totalChars,
                    xKiroPermitted &&
                        WorkspaceProviderSessionHealth.canSend(WorkspaceProviderRegistry.Id.XKIRO_FREE),
                    groqPermitted &&
                        WorkspaceProviderSessionHealth.canSend(WorkspaceProviderRegistry.Id.GROQ_FREE),
                )
            ) { "All permitted coding providers are cooling down or unavailable; no source was sent" }
            val prompt = WorkspaceGitHubSelfEditBatch.prompt(instruction, sources)
            propose(run, prompt)
        }
    }

    private fun readFilesAtSha(
        run: Long,
        token: String,
        headSha: String,
        paths: List<String>,
        index: Int = 0,
        collected: LinkedHashMap<String, String> = linkedMapOf(),
        onReady: (LinkedHashMap<String, String>) -> Unit,
    ) {
        require(paths.size in 1..WorkspaceGitHubSelfEditBatch.MAX_FILES) {
            "Selected source file count is outside the LYRA multi-file bound"
        }
        if (index >= paths.size) {
            onReady(collected)
            return
        }
        val saved = requireNotNull(connection)
        val path = WorkspaceGitHubWritePolicy.requirePath(paths[index])
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.fileContentRequest(
                token = token,
                repository = saved.repository,
                headSha = headSha,
                path = path,
            ),
            "GitHub source file could not be read; no write was attempted.",
        ) { response ->
            val source = WorkspaceGitHubConnector.readTextFile(response, path)
            require(collected.put(path, source) == null) { "Duplicate selected source path" }
            readFilesAtSha(run, token, headSha, paths, index + 1, collected, onReady)
        }
    }

    private fun propose(run: Long, prompt: String) {
        val route = requireNotNull(selectedProvider)
        sendProvider(
            run = run,
            route = route,
            prompt = prompt,
            sourcesForPatch = LinkedHashMap(originalSources),
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

    private fun consumeTaskBudget(
        run: Long,
        transform: (WorkspaceGitHubTaskBudget.State) -> WorkspaceGitHubTaskBudget.State,
    ): Boolean {
        val next = runCatching { transform(taskBudget) }.getOrElse { error ->
            val message = error.message ?: "GitHub coding task budget was exhausted."
            taskBudget = WorkspaceGitHubTaskBudget.withFailure(taskBudget, message)
            persistBudgetOnly()
            fail(run, null, message)
            return false
        }
        taskBudget = next
        persistBudgetOnly()
        listener.onEvent(
            WorkspaceWorkPhase.VERIFYING,
            "Task budget",
            WorkspaceGitHubTaskBudget.summary(taskBudget),
        )
        return true
    }

    private fun persistBudgetOnly() {
        val sha = preferences.getString(CP_SHA, null)?.trim()?.lowercase()
        if (sha == null || !CHECKPOINT_SHA.matches(sha)) return
        preferences.edit()
            .putInt(CP_BUDGET_PROVIDER_CALLS, taskBudget.providerCalls)
            .putInt(CP_BUDGET_REVIEW_CALLS, taskBudget.reviewCalls)
            .putInt(CP_BUDGET_FALLBACKS, taskBudget.fallbackSwitches)
            .putInt(CP_BUDGET_CI_REPAIRS, taskBudget.ciRepairs)
            .putInt(CP_BUDGET_COMMITS, taskBudget.commitAttempts)
            .putString(CP_LAST_FAILURE, taskBudget.lastFailure)
            .apply()
    }

    private fun sendProvider(
        run: Long,
        route: WorkspaceCodingRoleRouter.Provider,
        prompt: String,
        sourcesForPatch: Map<String, String>,
        expectedHead: String,
        repair: Boolean,
        fallbackUsed: Boolean,
    ) {
        require(sourcesForPatch.size in 1..WorkspaceGitHubSelfEditBatch.MAX_FILES) {
            "Provider source set is outside the LYRA multi-file bound"
        }
        val label = providerName(route)
        selectedProvider = route
        if (!repair) savePreCommitCheckpoint(expectedHead, sourcesForPatch.keys.toList())
        if (!consumeTaskBudget(run, WorkspaceGitHubTaskBudget::consumeProvider)) return
        listener.onEvent(
            if (repair) WorkspaceWorkPhase.RECOVERING else WorkspaceWorkPhase.CODING,
            if (repair) "Repairing failed exact CI" else "Preparing bounded GitHub edit",
            "$label · ${sourcesForPatch.size} file(s)",
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
                    if (!consumeTaskBudget(run, WorkspaceGitHubTaskBudget::consumeFallback)) return
                    sendProvider(run, alternate, prompt, sourcesForPatch, expectedHead, repair, true)
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
                        sendProvider(run, alternate, prompt, sourcesForPatch, expectedHead, repair, true)
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
                        sendProvider(run, alternate, prompt, sourcesForPatch, expectedHead, repair, true)
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
                    WorkspaceGitHubSelfEditBatch.prepare(raw, sourcesForPatch)
                }.getOrElse { error ->
                    fail(run, null, error.message ?: "$label coding response was rejected.")
                    return
                }
                listener.onEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    if (repair) "Repair patch accepted locally" else "Provider patch accepted locally",
                    label,
                )
                reviewOrCommit(
                    run = run,
                    primaryRoute = route,
                    prepared = prepared,
                    originals = sourcesForPatch,
                    expectedHead = expectedHead,
                    repair = repair,
                )
            }
        })
    }

    private fun reviewOrCommit(
        run: Long,
        primaryRoute: WorkspaceCodingRoleRouter.Provider,
        prepared: WorkspaceGitHubSelfEditBatch.Prepared,
        originals: Map<String, String>,
        expectedHead: String,
        repair: Boolean,
        revisionLineage: WorkspaceGitHubPatchReviewer.RevisionLineage? = null,
    ) {
        if (reviewRevisionAttempt > 0 && revisionLineage == null) {
            terminalFail(
                run,
                "Mandatory second review lineage was missing; no GitHub write was attempted.",
            )
            return
        }
        val reviewerRoute = alternateProvider(primaryRoute)
        if (reviewerRoute == null) {
            if (reviewRevisionAttempt > 0) {
                fail(
                    run,
                    null,
                    "Mandatory second review is unavailable after revision; no GitHub write was attempted.",
                )
                return
            }
            listener.onEvent(
                WorkspaceWorkPhase.VERIFYING,
                "Second-provider review unavailable",
                "Local validation + exact CI remain mandatory",
            )
            commit(run, prepared, expectedHead, repair)
            return
        }

        val plan = requireNotNull(codingPlan) { "Locked coding plan is missing before review" }
        val reviewPrompt = runCatching {
            WorkspaceGitHubPatchReviewer.prompt(
                instruction,
                plan,
                originals,
                prepared,
                revisionLineage,
            )
        }.getOrElse { error ->
            fail(run, null, error.message ?: "Reviewer context was rejected locally.")
            return
        }
        val reviewerLabel = providerName(reviewerRoute)
        listener.onEvent(
            WorkspaceWorkPhase.VERIFYING,
            "Second-provider QA review",
            "$reviewerLabel · read-only",
        )

        val request = runCatching {
            when (reviewerRoute) {
                WorkspaceCodingRoleRouter.Provider.XKIRO ->
                    WorkspaceXKiroFree.deliberationRequest(xKiroKey, reviewPrompt)
                WorkspaceCodingRoleRouter.Provider.GROQ ->
                    WorkspaceGroqFree.request(
                        groqKey,
                        listOf(
                            WorkspaceConversationStore.Message(
                                if (repair) "github-repair-review" else "github-patch-review",
                                "user",
                                reviewPrompt,
                                nowMs(),
                            )
                        ),
                    )
            }
        }.getOrElse {
            if (reviewRevisionAttempt > 0) {
                fail(
                    run,
                    null,
                    "Mandatory second review could not be prepared after revision; no GitHub write was attempted.",
                )
                return
            }
            listener.onEvent(
                WorkspaceWorkPhase.VERIFYING,
                "Reviewer unavailable before send",
                "Continuing with local validation + exact CI",
            )
            commit(run, prepared, expectedHead, repair)
            return
        }

        if (!consumeTaskBudget(run, WorkspaceGitHubTaskBudget::consumeReview)) return
        val client = when (reviewerRoute) {
            WorkspaceCodingRoleRouter.Provider.XKIRO -> WorkspaceXKiroFree.deliberationClient
            WorkspaceCodingRoleRouter.Provider.GROQ -> WorkspaceFreeAiSuggestion.client
        }
        val call = client.newCall(request)
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
                if (reviewRevisionAttempt > 0) {
                    fail(
                        run,
                        null,
                        "Mandatory second review had an uncertain network outcome after revision; " +
                            "no GitHub write was attempted.",
                    )
                    return
                }
                listener.onEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "Reviewer network unavailable",
                    "Continuing with local validation + exact CI",
                )
                commit(run, prepared, expectedHead, repair)
            }

            override fun onResponse(call: Call, response: Response) {
                synchronized(this@WorkspaceGitHubSelfEditFlow) {
                    if (run != generation || active !== call) {
                        response.close()
                        return
                    }
                    active = null
                }
                WorkspaceProviderSessionHealth.recordResponse(response)
                if (!response.isSuccessful) {
                    val status = response.code
                    response.close()
                    if (reviewRevisionAttempt > 0) {
                        fail(
                            run,
                            null,
                            "Mandatory second review HTTP $status was unavailable after revision; " +
                                "no GitHub write was attempted.",
                        )
                        return
                    }
                    listener.onEvent(
                        WorkspaceWorkPhase.VERIFYING,
                        "Reviewer HTTP $status unavailable",
                        "Continuing with local validation + exact CI",
                    )
                    commit(run, prepared, expectedHead, repair)
                    return
                }

                val parsedReview = runCatching {
                    val raw = when (reviewerRoute) {
                        WorkspaceCodingRoleRouter.Provider.XKIRO ->
                            WorkspaceXKiroFree.readDeliberation(response)
                        WorkspaceCodingRoleRouter.Provider.GROQ ->
                            WorkspaceGroqFree.read(response)
                    }
                    WorkspaceGitHubPatchReviewer.read(raw)
                }.getOrElse { error ->
                    fail(
                        run,
                        null,
                        "Reviewer returned an invalid QA contract; no GitHub write was attempted. " +
                            (error.message ?: "Unknown review parse error"),
                    )
                    return
                }
                val review = WorkspaceGitHubReviewerRevisionPhoneProbe.adjust(
                    reviewRevisionAttempt = reviewRevisionAttempt,
                    prepared = prepared,
                    review = parsedReview,
                )

                when (review.decision) {
                    WorkspaceGitHubPatchReviewer.Decision.ACCEPT -> {
                        listener.onEvent(
                            WorkspaceWorkPhase.VERIFYING,
                            "Reviewer ACCEPT",
                            reviewerLabel,
                        )
                        commit(run, prepared, expectedHead, repair)
                    }
                    WorkspaceGitHubPatchReviewer.Decision.REVISE -> {
                        if (reviewRevisionAttempt >= 1) {
                            terminalFail(
                                run,
                                "Reviewer requested another revision after the single bounded revision: " +
                                    review.summary + ". No GitHub write was attempted; this task is terminal and will not auto-resume.",
                            )
                            return
                        }
                        reviseAfterReview(
                            run = run,
                            primaryRoute = primaryRoute,
                            originals = originals,
                            previousPrepared = prepared,
                            expectedHead = expectedHead,
                            repair = repair,
                            review = review,
                        )
                    }
                    WorkspaceGitHubPatchReviewer.Decision.REJECT -> {
                        terminalFail(
                            run,
                            "Reviewer rejected the proposed patch before commit: " + review.summary +
                                ". No GitHub write was attempted; this task is terminal and will not auto-resume.",
                        )
                    }
                }
            }
        })
    }

    private fun reviseAfterReview(
        run: Long,
        primaryRoute: WorkspaceCodingRoleRouter.Provider,
        originals: Map<String, String>,
        previousPrepared: WorkspaceGitHubSelfEditBatch.Prepared,
        expectedHead: String,
        repair: Boolean,
        review: WorkspaceGitHubPatchReviewer.Review,
    ) {
        val label = providerName(primaryRoute)
        val revisionPrompt = runCatching {
            WorkspaceGitHubSelfEditBatch.reviewRevisionPrompt(
                message = instruction,
                sources = originals,
                previousPrepared = previousPrepared,
                reviewSummary = review.summary,
                reviewRisks = review.risks,
            )
        }.getOrElse { error ->
            fail(run, null, error.message ?: "Reviewer revision context was rejected locally.")
            return
        }
        listener.onEvent(
            WorkspaceWorkPhase.RECOVERING,
            "Applying one reviewer-requested revision",
            "$label · pre-commit",
        )
        val request = runCatching {
            when (primaryRoute) {
                WorkspaceCodingRoleRouter.Provider.XKIRO ->
                    WorkspaceXKiroFree.deliberationRequest(xKiroKey, revisionPrompt)
                WorkspaceCodingRoleRouter.Provider.GROQ ->
                    WorkspaceGroqFree.request(
                        groqKey,
                        listOf(
                            WorkspaceConversationStore.Message(
                                "github-review-revision",
                                "user",
                                revisionPrompt,
                                nowMs(),
                            )
                        ),
                    )
            }
        }.getOrElse { error ->
            fail(
                run,
                null,
                "Primary coder could not prepare the reviewer-requested revision; " +
                    "no GitHub write was attempted. " +
                    (error.message ?: ""),
            )
            return
        }

        if (!consumeTaskBudget(run, WorkspaceGitHubTaskBudget::consumeProvider)) return
        val client = when (primaryRoute) {
            WorkspaceCodingRoleRouter.Provider.XKIRO -> WorkspaceXKiroFree.deliberationClient
            WorkspaceCodingRoleRouter.Provider.GROQ -> WorkspaceFreeAiSuggestion.client
        }
        val call = client.newCall(request)
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
                fail(
                    run,
                    null,
                    "Primary coder revision had an uncertain network outcome; " +
                        "no automatic resend and no GitHub write was attempted.",
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
                WorkspaceProviderSessionHealth.recordResponse(response)
                if (!response.isSuccessful) {
                    val status = response.code
                    response.close()
                    fail(
                        run,
                        null,
                        "Primary coder revision HTTP $status failed; no GitHub write was attempted.",
                    )
                    return
                }
                val revised = runCatching {
                    val raw = when (primaryRoute) {
                        WorkspaceCodingRoleRouter.Provider.XKIRO ->
                            WorkspaceXKiroFree.readDeliberation(response)
                        WorkspaceCodingRoleRouter.Provider.GROQ ->
                            WorkspaceGroqFree.read(response)
                    }
                    WorkspaceGitHubSelfEditBatch.requireMaterialRevision(
                        previous = previousPrepared,
                        revised = WorkspaceGitHubSelfEditBatch.prepare(raw, originals),
                    )
                }.getOrElse { error ->
                    terminalFail(
                        run,
                        "Reviewer-requested revision was rejected locally; no GitHub write was attempted. " +
                            (error.message ?: "") +
                            " This task is terminal and will not auto-resume.",
                    )
                    return
                }
                reviewRevisionAttempt = 1
                savePreCommitCheckpoint(expectedHead, originals.keys.toList())
                listener.onEvent(
                    WorkspaceWorkPhase.VERIFYING,
                    "Revised patch accepted locally",
                    "$label · mandatory second review",
                )
                reviewOrCommit(
                    run = run,
                    primaryRoute = primaryRoute,
                    prepared = revised,
                    originals = originals,
                    expectedHead = expectedHead,
                    repair = repair,
                    revisionLineage = WorkspaceGitHubPatchReviewer.RevisionLineage(
                        previousPrepared = previousPrepared,
                        previousReview = review,
                    ),
                )
            }
        })
    }

    private fun commit(
        run: Long,
        prepared: WorkspaceGitHubSelfEditBatch.Prepared,
        expectedHead: String,
        repair: Boolean,
    ) {
        if (!consumeTaskBudget(run, WorkspaceGitHubTaskBudget::consumeCommit)) return
        val checked = requireNotNull(access)
        val plan = WorkspaceGitHubWritePolicy.commitPlan(
            expectedHead = expectedHead,
            message = WorkspaceGitHubSelfEditBatch.commitMessage(prepared.files, repair),
            files = prepared.files,
        )
        listener.onEvent(
            WorkspaceWorkPhase.CODING,
            "Committing protected feature-branch edit",
            prepared.files.size.toString() + " file(s)",
        )
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.commitRequest(pairing, plan),
            "GitHub commit was not confirmed. Do not retry automatically.",
        ) { response ->
            val receipt = WorkspaceGitHubConnector.readCommitReceipt(response)
            val expectedPaths = prepared.files.map { it.path.lowercase(Locale.US) }.toSet()
            val receivedPaths = receipt.files.map { it.lowercase(Locale.US) }.toSet()
            require(receipt.previousHead == expectedHead &&
                receipt.branch == checked.branch &&
                receipt.files.size == prepared.files.size &&
                receivedPaths == expectedPaths) {
                "GitHub commit receipt did not match the authorized self-edit"
            }
            access = checked.copy(headSha = receipt.commitSha)
            if (repair) repairAttempt = 1
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
            if (!consumeTaskBudget(run, WorkspaceGitHubTaskBudget::consumeCiRepair)) {
                return@dispatch
            }
            ciWatching = false
            listener.onEvent(
                WorkspaceWorkPhase.RECOVERING,
                "Reading failed commit for one bounded repair",
                "CI #${workflow.runNumber} · ${receipt.files.size} file(s) · " +
                    receipt.commitSha.take(12),
            )
            readFilesAtSha(
                run = run,
                token = token,
                headSha = receipt.commitSha,
                paths = receipt.files,
            ) { currentSources ->
                val prompt = WorkspaceGitHubSelfEditBatch.prompt(
                    message = instruction,
                    sources = currentSources,
                    ciFailureSummary = summary,
                )
                sendProvider(
                    run = run,
                    route = requireNotNull(selectedProvider),
                    prompt = prompt,
                    sourcesForPatch = currentSources,
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
        val plan = codingPlan
        if (plan == null) {
            fail(run, null, "Coding completion plan was missing; task is not done.")
            return
        }
        val verified = runCatching {
            WorkspaceGitHubCodingPlan.verifyCompletion(plan, result.commit, result.workflow)
        }
        if (verified.isFailure) {
            fail(
                run,
                null,
                verified.exceptionOrNull()?.message
                    ?: "Coding completion criteria were not satisfied; task is not done.",
            )
            return
        }
        listener.onEvent(
            WorkspaceWorkPhase.VERIFYING,
            "Completion criteria satisfied",
            "Selected scope + exact CI GREEN verified",
        )
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

    private fun terminalFail(run: Long, message: String) {
        fail(run, null, message, preserveCheckpoint = false)
    }

    private fun fail(
        run: Long,
        call: Call?,
        message: String,
        preserveCheckpoint: Boolean = true,
    ) {
        synchronized(this) {
            if (run != generation || (call != null && active !== call)) return
            taskBudget = runCatching {
                WorkspaceGitHubTaskBudget.withFailure(taskBudget, message)
            }.getOrDefault(taskBudget)
            if (preserveCheckpoint) persistBudgetOnly() else clearCheckpoint()
            active = null
            ciWatching = false
            pollHandler.removeCallbacksAndMessages(null)
            clearState()
        }
        listener.onError(message)
    }

    private fun savePreCommitCheckpoint(
        expectedHead: String,
        paths: List<String>,
    ) {
        val saved = requireNotNull(connection)
        require(paths.size in 1..WorkspaceGitHubSelfEditBatch.MAX_FILES) {
            "Pre-commit file count is outside the LYRA multi-file bound"
        }
        val checkedPaths = paths.map(WorkspaceGitHubWritePolicy::requirePath)
        require(checkedPaths.map { it.lowercase(Locale.US) }.toSet().size == checkedPaths.size) {
            "Pre-commit checkpoint contains duplicate paths"
        }
        val provider = requireNotNull(selectedProvider).name
        require(CHECKPOINT_SHA.matches(expectedHead)) { "Pre-commit checkpoint SHA is invalid" }
        preferences.edit()
            .putString(CP_SHA, expectedHead)
            .putString(CP_PREVIOUS_HEAD, expectedHead)
            .putString(CP_REPO, saved.repository)
            .putString(CP_BRANCH, saved.branch)
            .putString(CP_PATH, checkedPaths.first())
            .putString(CP_PATHS, checkedPaths.joinToString("\n"))
            .putString(CP_PROVIDER, provider)
            .putString(CP_PHASE, "pre_commit")
            .putString(CP_INSTRUCTION, instruction)
            .putInt(CP_REPAIR_ATTEMPT, 0)
            .putInt(CP_REVIEW_REVISION_ATTEMPT, reviewRevisionAttempt)
            .putInt(CP_BUDGET_PROVIDER_CALLS, taskBudget.providerCalls)
            .putInt(CP_BUDGET_REVIEW_CALLS, taskBudget.reviewCalls)
            .putInt(CP_BUDGET_FALLBACKS, taskBudget.fallbackSwitches)
            .putInt(CP_BUDGET_CI_REPAIRS, taskBudget.ciRepairs)
            .putInt(CP_BUDGET_COMMITS, taskBudget.commitAttempts)
            .putString(CP_LAST_FAILURE, taskBudget.lastFailure)
            .apply()
    }

    private fun saveCheckpoint(
        receipt: WorkspaceGitHubConnector.CommitReceipt,
        phase: String,
    ) {
        require(phase in CHECKPOINT_PHASES) { "Unsupported coding checkpoint phase" }
        val provider = requireNotNull(selectedProvider).name
        require(receipt.files.size in 1..WorkspaceGitHubSelfEditBatch.MAX_FILES) {
            "Coding checkpoint file count is outside the LYRA multi-file bound"
        }
        preferences.edit()
            .putString(CP_SHA, receipt.commitSha)
            .putString(CP_PREVIOUS_HEAD, receipt.previousHead)
            .putString(CP_REPO, receipt.repository)
            .putString(CP_BRANCH, receipt.branch)
            .putString(CP_PATH, receipt.files.first())
            .putString(CP_PATHS, receipt.files.joinToString("\n"))
            .putString(CP_PROVIDER, provider)
            .putString(CP_PHASE, phase)
            .putString(CP_INSTRUCTION, instruction)
            .putInt(CP_REPAIR_ATTEMPT, repairAttempt)
            .putInt(CP_REVIEW_REVISION_ATTEMPT, reviewRevisionAttempt)
            .putInt(CP_BUDGET_PROVIDER_CALLS, taskBudget.providerCalls)
            .putInt(CP_BUDGET_REVIEW_CALLS, taskBudget.reviewCalls)
            .putInt(CP_BUDGET_FALLBACKS, taskBudget.fallbackSwitches)
            .putInt(CP_BUDGET_CI_REPAIRS, taskBudget.ciRepairs)
            .putInt(CP_BUDGET_COMMITS, taskBudget.commitAttempts)
            .putString(CP_LAST_FAILURE, taskBudget.lastFailure)
            .apply()
    }

    private fun loadCheckpoint(): Checkpoint? {
        val sha = preferences.getString(CP_SHA, null)?.trim()?.lowercase() ?: return null
        val previousHead = preferences.getString(CP_PREVIOUS_HEAD, null)?.trim()?.lowercase()
            ?: throw IllegalArgumentException("Checkpoint previous head is missing")
        require(CHECKPOINT_SHA.matches(sha) && CHECKPOINT_SHA.matches(previousHead)) {
            "Checkpoint SHA is invalid"
        }
        val repository = preferences.getString(CP_REPO, null)?.trim().orEmpty()
        val branch = preferences.getString(CP_BRANCH, null)?.trim().orEmpty()
        val rawPaths = preferences.getString(CP_PATHS, null)
            ?.split('\n')
            ?.filter(String::isNotBlank)
            ?: listOf(preferences.getString(CP_PATH, null)?.trim().orEmpty())
        require(rawPaths.size in 1..WorkspaceGitHubSelfEditBatch.MAX_FILES) {
            "Checkpoint file count is invalid"
        }
        val paths = rawPaths.map(WorkspaceGitHubWritePolicy::requirePath)
        require(paths.map { it.lowercase(Locale.US) }.toSet().size == paths.size) {
            "Checkpoint contains duplicate paths"
        }
        require(repository.isNotBlank() && branch == "agent/myra-phase-1") {
            "Checkpoint GitHub binding is invalid"
        }
        val provider = WorkspaceCodingRoleRouter.Provider.valueOf(
            preferences.getString(CP_PROVIDER, null)?.trim().orEmpty()
        )
        val phase = preferences.getString(CP_PHASE, null)?.trim().orEmpty()
        require(phase in CHECKPOINT_PHASES) { "Checkpoint phase is invalid" }
        val savedInstruction = preferences.getString(CP_INSTRUCTION, null)?.trim().orEmpty()
        require(WorkspaceGitHubSelfEdit.isExplicitRequest(savedInstruction)) {
            "Checkpoint instruction is invalid"
        }
        val attempts = preferences.getInt(CP_REPAIR_ATTEMPT, 0)
        require(attempts in 0..1) { "Checkpoint repair count is invalid" }
        val reviewAttempts = preferences.getInt(CP_REVIEW_REVISION_ATTEMPT, 0)
        require(reviewAttempts in 0..1) { "Checkpoint review revision count is invalid" }
        val budget = WorkspaceGitHubTaskBudget.validate(
            WorkspaceGitHubTaskBudget.State(
                providerCalls = preferences.getInt(CP_BUDGET_PROVIDER_CALLS, 0),
                reviewCalls = preferences.getInt(CP_BUDGET_REVIEW_CALLS, 0),
                fallbackSwitches = preferences.getInt(CP_BUDGET_FALLBACKS, 0),
                ciRepairs = preferences.getInt(CP_BUDGET_CI_REPAIRS, 0),
                commitAttempts = preferences.getInt(CP_BUDGET_COMMITS, 0),
                lastFailure = preferences.getString(CP_LAST_FAILURE, "").orEmpty(),
            )
        )
        return Checkpoint(
            receipt = WorkspaceGitHubConnector.CommitReceipt(
                repository = repository,
                branch = branch,
                previousHead = previousHead,
                commitSha = sha,
                files = paths,
            ),
            provider = provider,
            phase = phase,
            instruction = savedInstruction,
            repairAttempt = attempts,
            reviewRevisionAttempt = reviewAttempts,
            taskBudget = budget,
        )
    }

    private fun clearCheckpoint() {
        preferences.edit()
            .remove(CP_SHA)
            .remove(CP_PREVIOUS_HEAD)
            .remove(CP_REPO)
            .remove(CP_BRANCH)
            .remove(CP_PATH)
            .remove(CP_PATHS)
            .remove(CP_PROVIDER)
            .remove(CP_PHASE)
            .remove(CP_INSTRUCTION)
            .remove(CP_REPAIR_ATTEMPT)
            .remove(CP_REVIEW_REVISION_ATTEMPT)
            .remove(CP_BUDGET_PROVIDER_CALLS)
            .remove(CP_BUDGET_REVIEW_CALLS)
            .remove(CP_BUDGET_FALLBACKS)
            .remove(CP_BUDGET_CI_REPAIRS)
            .remove(CP_BUDGET_COMMITS)
            .remove(CP_LAST_FAILURE)
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
        reviewRevisionAttempt = 0
        taskBudget = WorkspaceGitHubTaskBudget.State()
        grant = null
        access = null
        candidates = emptyList()
        originalSources.clear()
        codingPlan = null
    }
}
