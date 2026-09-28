package com.myra.assistant.ui.workspace

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.myra.assistant.ai.ApiKeyStore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * One real CI benchmark pass across configured providers.
 *
 * Each provider receives the same synthetic source only. A valid bounded expression is committed as
 * one temporary target-file revision on agent/myra-phase-1. GitHub Actions then compiles/tests that
 * exact provider commit. After all providers, the known-good baseline is restored in a final commit.
 */
internal class WorkspaceProviderCiBenchmarkRunner(
    context: Context,
    private val keys: ApiKeyStore,
    private val listener: Listener,
) {
    interface Listener {
        fun onUpdate(text: String, done: Boolean)
    }

    private data class Seat(
        val name: String,
        val slug: String,
        val available: () -> Pair<Boolean, String>,
        val request: (String, String) -> Request,
        val client: OkHttpClient,
        val read: (Response) -> String,
    )

    companion object {
        private const val CI_POLL_MS = 8_000L
        private const val MAX_CI_POLLS = 120
        private const val MAX_CI_POLL_ERRORS = 3
    }

    private val prefs = context.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
    private val pollHandler = Handler(Looper.getMainLooper())
    private val store = WorkspaceConnectorCredentialStore(context)
    private val lines = linkedMapOf<String, String>()
    private var generation = 0L
    private var active: Call? = null
    private var running = false
    private var pairing = ""
    private var connection: WorkspaceConnectorCredentialStore.GitHubConnection? = null
    private var wroteProviderCommit = false
    private var seedLine = "waiting"
    private var seedFailureSummary = ""

    val isRunning: Boolean get() = synchronized(this) { running }

    private fun message(prompt: String) = WorkspaceConversationStore.Message(
        "provider-real-ci-repair-benchmark",
        "user",
        prompt,
        0L,
    )

    private fun firstKey(slot: String): String =
        runCatching { keys.get(slot) }.getOrDefault("")
            .split(',').asSequence().map(String::trim).firstOrNull(String::isNotBlank).orEmpty()

    private fun seats(): List<Seat> = listOf(
        Seat(
            "OpenRouter · openrouter/free",
            "openrouter",
            {
                val key = firstKey(ApiKeyStore.OPENROUTER)
                Pair(WorkspaceCodingAutoFallback.validKey(key), key)
            },
            { key, prompt -> WorkspaceFreeAiSuggestion.request(key, prompt) },
            WorkspaceFreeAiSuggestion.client,
            WorkspaceFreeAiSuggestion::readResponse,
        ),
        Seat(
            "Groq · " + WorkspaceGroqFree.MODEL,
            "groq",
            {
                val key = firstKey(ApiKeyStore.GROQ)
                Pair(
                    prefs.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false) &&
                        WorkspaceCodingAutoFallback.validKey(key),
                    key,
                )
            },
            { key, prompt -> WorkspaceGroqFree.request(key, listOf(message(prompt))) },
            WorkspaceFreeAiSuggestion.client,
            WorkspaceGroqFree::read,
        ),
        Seat(
            "LLM7 · " + WorkspaceLlm7Free.MODEL,
            "llm7",
            {
                val key = firstKey(ApiKeyStore.LLM7)
                // Running REAL CI is separate one-time consent for this fixed synthetic fixture.
                // It never enables LLM7 for normal chat, project source, memory, voice or photos.
                Pair(WorkspaceLlm7Free.validKey(key), key)
            },
            { key, prompt -> WorkspaceLlm7Free.request(key, listOf(message(prompt))) },
            WorkspaceLlm7Free.client,
            WorkspaceLlm7Free::read,
        ),
        Seat(
            "xKiro · " + WorkspaceXKiroFree.MODEL,
            "xkiro",
            {
                val key = firstKey(ApiKeyStore.XKIRO)
                Pair(WorkspaceXKiroFree.validKey(key), key)
            },
            { key, prompt -> WorkspaceXKiroFree.deliberationRequest(key, prompt) },
            WorkspaceXKiroFree.deliberationClient,
            WorkspaceXKiroFree::readDeliberation,
        ),
        Seat(
            "Z.ai · " + WorkspaceZaiFree.DEFAULT_TEXT_MODEL,
            "zai",
            {
                val key = firstKey(ApiKeyStore.ZAI)
                Pair(
                    prefs.getBoolean(WorkspaceZaiFree.CODING_PREFERENCE_KEY, false) &&
                        WorkspaceZaiFree.validKey(key),
                    key,
                )
            },
            { key, prompt ->
                WorkspaceZaiFree.deliberationRequest(
                    key = key,
                    prompt = prompt,
                    textModel = WorkspaceZaiFree.DEFAULT_TEXT_MODEL,
                    sourceIncluded = false,
                    sourceApproved = false,
                )
            },
            WorkspaceZaiFree.client,
            WorkspaceZaiFree::read,
        ),
    )

    @Synchronized fun start() {
        if (running) return
        val saved = runCatching { store.loadGitHub() }.getOrNull()
        val secret = saved?.pairingSecret
        if (saved == null || secret == null) {
            listener.onUpdate(
                "Real CI benchmark requires the connected LYRA GitHub App with protected C2 write access.",
                true,
            )
            return
        }
        require(saved.branch == "agent/myra-phase-1") {
            "Real CI benchmark is locked to agent/myra-phase-1"
        }
        generation += 1
        running = true
        connection = saved
        pairing = secret
        wroteProviderCommit = false
        seedLine = "waiting"
        seedFailureSummary = ""
        lines.clear()
        seats().forEach { lines[it.name] = "waiting" }
        render("Creating one deliberate synthetic CI failure…", done = false)
        seedFailure(generation)
    }

    @Synchronized fun cancel() {
        generation += 1
        active?.cancel()
        active = null
        pollHandler.removeCallbacksAndMessages(null)
        running = false
    }

    private fun render(label: String, done: Boolean) {
        val body = buildList {
            add("REAL CI REPAIR LOOP · synthetic source only · exact-SHA Actions watch · main untouched")
            add(label)
            add("CI seed — " + seedLine)
            lines.forEach { (name, value) -> add(name + " — " + value) }
        }.joinToString("\n")
        listener.onUpdate(body, done)
    }

    private fun runSeat(run: Long, seats: List<Seat>, index: Int) {
        if (index >= seats.size) {
            if (wroteProviderCommit) restoreBaseline(run) else finish(run, "No provider commit was created.")
            return
        }
        val seat = seats[index]
        val available = runCatching { seat.available() }.getOrDefault(Pair(false, ""))
        if (!available.first) {
            lines[seat.name] = "SKIPPED (key/required Free opt-in unavailable)"
            render("Preparing provider " + (index + 1) + "/" + seats.size, false)
            runSeat(run, seats, index + 1)
            return
        }

        lines[seat.name] = "repairing from real CI failure…"
        render("Repair provider " + (index + 1) + "/" + seats.size, false)
        val prompt = WorkspaceProviderCiBenchmark.repairPrompt(seedFailureSummary)
        val modelStarted = SystemClock.elapsedRealtime()
        val request = runCatching { seat.request(available.second, prompt) }.getOrElse {
            lines[seat.name] = "REQUEST BLOCKED · " + (it.message ?: "local validation").take(100)
            runSeat(run, seats, index + 1)
            return
        }
        dispatch(
            run,
            seat.client,
            request,
            onFailure = {
                lines[seat.name] = "MODEL ERROR · " + it.take(100)
                runSeat(run, seats, index + 1)
            },
            onResponse = { response ->
                val prepared = runCatching {
                    WorkspaceProviderCiBenchmark.prepare(seat.read(response))
                }.getOrElse {
                    lines[seat.name] = "OUTPUT REJECTED · " +
                        (it.message ?: "invalid coding reply").take(100)
                    runSeat(run, seats, index + 1)
                    return@dispatch
                }
                val modelMs = SystemClock.elapsedRealtime() - modelStarted
                commitProvider(run, seats, index, seat, prepared.source, modelMs)
            },
        )
    }

    private fun commitProvider(
        run: Long,
        seats: List<Seat>,
        index: Int,
        seat: Seat,
        source: String,
        modelMs: Long,
    ) {
        lines[seat.name] = "code accepted · GitHub preflight…"
        render("Provider " + (index + 1) + "/" + seats.size, false)
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.writeAccessRequest(pairing),
            onFailure = {
                lines[seat.name] = "GITHUB PREFLIGHT ERROR · " + it.take(100)
                runSeat(run, seats, index + 1)
            },
            onResponse = { response ->
                val access = runCatching {
                    WorkspaceGitHubConnector.readWriteAccess(response)
                }.getOrElse {
                    lines[seat.name] = "GITHUB PREFLIGHT ERROR · " +
                        (it.message ?: "invalid response").take(100)
                    runSeat(run, seats, index + 1)
                    return@dispatch
                }
                val saved = requireNotNull(connection)
                require(access.repository.equals(saved.repository, ignoreCase = true) &&
                    access.branch == saved.branch && access.prBase == "main") {
                    "GitHub benchmark binding changed"
                }
                val plan = WorkspaceGitHubWritePolicy.commitPlan(
                    expectedHead = access.headSha,
                    message = "test: real CI provider benchmark " + seat.slug,
                    files = listOf(
                        WorkspaceGitHubWritePolicy.FileChange(
                            WorkspaceProviderCiBenchmark.TARGET_PATH,
                            source,
                        )
                    ),
                )
                dispatch(
                    run,
                    WorkspaceGitHubConnector.client,
                    WorkspaceGitHubConnector.commitRequest(pairing, plan),
                    onFailure = {
                        lines[seat.name] = "COMMIT ERROR · " + it.take(100)
                        runSeat(run, seats, index + 1)
                    },
                    onResponse = { commitResponse ->
                        val receipt = WorkspaceGitHubConnector.readCommitReceipt(commitResponse)
                        require(receipt.previousHead == access.headSha &&
                            receipt.branch == access.branch &&
                            receipt.files == listOf(WorkspaceProviderCiBenchmark.TARGET_PATH)) {
                            "Provider benchmark commit receipt mismatch"
                        }
                        wroteProviderCommit = true
                        lines[seat.name] =
                            "COMMITTED " + receipt.commitSha.take(12) + " · waiting exact CI"
                        render("Repair commit created; watching exact GitHub Actions SHA.", false)
                        awaitCi(
                            run = run,
                            commitSha = receipt.commitSha,
                            label = seat.name,
                            onCompleted = { workflow, _ ->
                                if (workflow.conclusion == "success") {
                                    lines[seat.name] = "PASS · model " + modelMs + "ms · CI #" +
                                        workflow.runNumber + " GREEN"
                                } else {
                                    lines[seat.name] = "FAIL · model " + modelMs + "ms · CI #" +
                                        workflow.runNumber + " " +
                                        (workflow.conclusion ?: "unknown").uppercase()
                                }
                                runSeat(run, seats, index + 1)
                            },
                            onFailure = {
                                lines[seat.name] = "CI WATCH ERROR · " + it.take(100)
                                runSeat(run, seats, index + 1)
                            },
                        )
                    },
                )
            },
        )
    }

    private fun seedFailure(run: Long) {
        seedLine = "GitHub preflight…"
        render("Creating deliberate failing fixture on protected feature branch.", false)
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.writeAccessRequest(pairing),
            onFailure = { finish(run, "Seed preflight failed: " + it.take(120)) },
            onResponse = { response ->
                val access = WorkspaceGitHubConnector.readWriteAccess(response)
                val saved = requireNotNull(connection)
                require(access.repository.equals(saved.repository, ignoreCase = true) &&
                    access.branch == saved.branch && access.prBase == "main") {
                    "GitHub repair benchmark binding changed"
                }
                val plan = WorkspaceGitHubWritePolicy.commitPlan(
                    expectedHead = access.headSha,
                    message = "test: seed real CI repair benchmark failure",
                    files = listOf(
                        WorkspaceGitHubWritePolicy.FileChange(
                            WorkspaceProviderCiBenchmark.TARGET_PATH,
                            WorkspaceProviderCiBenchmark.FAILURE_SOURCE,
                        )
                    ),
                )
                dispatch(
                    run,
                    WorkspaceGitHubConnector.client,
                    WorkspaceGitHubConnector.commitRequest(pairing, plan),
                    onFailure = { finish(run, "Seed commit failed: " + it.take(120)) },
                    onResponse = { commitResponse ->
                        val receipt = WorkspaceGitHubConnector.readCommitReceipt(commitResponse)
                        require(receipt.previousHead == access.headSha &&
                            receipt.branch == access.branch &&
                            receipt.files == listOf(WorkspaceProviderCiBenchmark.TARGET_PATH)) {
                            "Repair benchmark seed receipt mismatch"
                        }
                        wroteProviderCommit = true
                        seedLine = "COMMITTED " + receipt.commitSha.take(12) + " · waiting expected failure"
                        render("Watching deliberate seed commit until Actions finishes.", false)
                        awaitCi(
                            run = run,
                            commitSha = receipt.commitSha,
                            label = "CI seed",
                            onCompleted = { workflow, token ->
                                if (workflow.conclusion != "failure") {
                                    seedLine = "UNEXPECTED " +
                                        (workflow.conclusion ?: "unknown").uppercase() +
                                        " · CI #" + workflow.runNumber
                                    restoreBaseline(run)
                                    return@awaitCi
                                }
                                readFailureContext(
                                    run = run,
                                    token = token,
                                    workflow = workflow,
                                    onReady = { summary ->
                                        seedFailureSummary = summary
                                        seedLine = "EXPECTED FAIL · CI #" + workflow.runNumber +
                                            " · bounded failure captured"
                                        render("Real failure captured. Starting provider repair passes.", false)
                                        runSeat(run, seats(), 0)
                                    },
                                    onFailure = {
                                        seedLine = "FAILURE CONTEXT ERROR · " + it.take(90)
                                        restoreBaseline(run)
                                    },
                                )
                            },
                            onFailure = {
                                seedLine = "CI WATCH ERROR · " + it.take(90)
                                restoreBaseline(run)
                            },
                        )
                    },
                )
            },
        )
    }

    private fun awaitCi(
        run: Long,
        commitSha: String,
        label: String,
        onCompleted: (WorkspaceGitHubConnector.WorkflowRun, String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.installationTokenRequest(pairing),
            onFailure = onFailure,
            onResponse = { response ->
                val grant = WorkspaceGitHubConnector.readInstallationGrant(response)
                val saved = requireNotNull(connection)
                require(grant.repository.equals(saved.repository, ignoreCase = true) &&
                    grant.branch == saved.branch) {
                    "GitHub Actions read grant binding changed"
                }
                pollCi(
                    run = run,
                    token = grant.accessToken,
                    commitSha = commitSha,
                    label = label,
                    attempt = 0,
                    errors = 0,
                    onCompleted = onCompleted,
                    onFailure = onFailure,
                )
            },
        )
    }

    private fun pollCi(
        run: Long,
        token: String,
        commitSha: String,
        label: String,
        attempt: Int,
        errors: Int,
        onCompleted: (WorkspaceGitHubConnector.WorkflowRun, String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (attempt >= MAX_CI_POLLS) {
            onFailure("Timed out waiting for exact Actions run")
            return
        }
        val saved = requireNotNull(connection)
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.workflowRunsRequest(
                token = token,
                repository = saved.repository,
                branch = saved.branch,
            ),
            onFailure = {
                if (errors < MAX_CI_POLL_ERRORS) {
                    scheduleCiPoll(
                        run, token, commitSha, label, attempt + 1, errors + 1,
                        onCompleted, onFailure,
                    )
                } else {
                    onFailure("Actions polling failed repeatedly: " + it.take(90))
                }
            },
            onResponse = { response ->
                val workflow = WorkspaceGitHubConnector.readWorkflowRunForHead(response, commitSha)
                if (workflow == null) {
                    render(label + " · waiting for exact Actions run " + commitSha.take(12), false)
                    scheduleCiPoll(
                        run, token, commitSha, label, attempt + 1, 0,
                        onCompleted, onFailure,
                    )
                    return@dispatch
                }
                if (workflow.status != "completed") {
                    render(
                        label + " · CI #" + workflow.runNumber + " " + workflow.status,
                        false,
                    )
                    scheduleCiPoll(
                        run, token, commitSha, label, attempt + 1, 0,
                        onCompleted, onFailure,
                    )
                    return@dispatch
                }
                onCompleted(workflow, token)
            },
        )
    }

    private fun scheduleCiPoll(
        run: Long,
        token: String,
        commitSha: String,
        label: String,
        attempt: Int,
        errors: Int,
        onCompleted: (WorkspaceGitHubConnector.WorkflowRun, String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        pollHandler.postDelayed({
            val alive = synchronized(this) { run == generation && running }
            if (alive) {
                pollCi(
                    run, token, commitSha, label, attempt, errors,
                    onCompleted, onFailure,
                )
            }
        }, CI_POLL_MS)
    }

    private fun readFailureContext(
        run: Long,
        token: String,
        workflow: WorkspaceGitHubConnector.WorkflowRun,
        onReady: (String) -> Unit,
        onFailure: (String) -> Unit,
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
            onFailure = onFailure,
            onResponse = { response ->
                val failure = WorkspaceGitHubConnector.readWorkflowFailure(response, workflow)
                onReady(failure.boundedSummary())
            },
        )
    }

    private fun restoreBaseline(run: Long) {
        render("Restoring known-good benchmark baseline…", false)
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.writeAccessRequest(pairing),
            onFailure = { finish(run, "Cleanup preflight failed: " + it.take(120)) },
            onResponse = { response ->
                val access = WorkspaceGitHubConnector.readWriteAccess(response)
                val plan = WorkspaceGitHubWritePolicy.commitPlan(
                    expectedHead = access.headSha,
                    message = "test: restore real CI provider benchmark baseline",
                    files = listOf(
                        WorkspaceGitHubWritePolicy.FileChange(
                            WorkspaceProviderCiBenchmark.TARGET_PATH,
                            WorkspaceProviderCiBenchmark.BASELINE_SOURCE,
                        )
                    ),
                )
                dispatch(
                    run,
                    WorkspaceGitHubConnector.client,
                    WorkspaceGitHubConnector.commitRequest(pairing, plan),
                    onFailure = { finish(run, "Cleanup commit failed: " + it.take(120)) },
                    onResponse = { commitResponse ->
                        val receipt = WorkspaceGitHubConnector.readCommitReceipt(commitResponse)
                        render(
                            "Baseline restored at " + receipt.commitSha.take(12) +
                                "; watching final cleanup CI.",
                            false,
                        )
                        awaitCi(
                            run = run,
                            commitSha = receipt.commitSha,
                            label = "Cleanup",
                            onCompleted = { workflow, _ ->
                                val conclusion = workflow.conclusion ?: "unknown"
                                finish(
                                    run,
                                    "Repair benchmark complete. Baseline " +
                                        receipt.commitSha.take(12) + " · CI #" +
                                        workflow.runNumber + " " + conclusion.uppercase(),
                                )
                            },
                            onFailure = {
                                finish(
                                    run,
                                    "Baseline restored at " + receipt.commitSha.take(12) +
                                        " but cleanup CI watch failed: " + it.take(100),
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    private fun dispatch(
        run: Long,
        client: OkHttpClient,
        request: Request,
        onFailure: (String) -> Unit,
        onResponse: (Response) -> Unit,
    ) {
        val call = client.newCall(request)
        synchronized(this) {
            if (run != generation || !running) return
            active = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@WorkspaceProviderCiBenchmarkRunner) {
                    if (run != generation || active !== call) return
                    active = null
                }
                onFailure(e.message ?: "network request failed")
            }

            override fun onResponse(call: Call, response: Response) {
                synchronized(this@WorkspaceProviderCiBenchmarkRunner) {
                    if (run != generation || active !== call) {
                        response.close()
                        return
                    }
                    active = null
                }
                runCatching { onResponse(response) }
                    .onFailure {
                        runCatching { response.close() }
                        onFailure(it.message ?: "response rejected")
                    }
            }
        })
    }

    private fun finish(run: Long, message: String) {
        synchronized(this) {
            if (run != generation) return
            active = null
            pollHandler.removeCallbacksAndMessages(null)
            running = false
        }
        render(message, done = true)
    }
}
