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
 * Each provider receives the same synthetic source only. It must solve two coordinated function
 * contracts. Every accepted first attempt is committed and exact-SHA CI tested; one failed CI attempt
 * may be repaired once by the same provider using bounded failure evidence. After all providers, the
 * known-good baseline is restored in a final exact-CI-verified commit.
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
        render("Creating one deliberate two-file finalist CI failure…", done = false)
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
            add("REAL CI FINALIST MULTI-FILE LOOP · Groq vs xKiro · synthetic source only · exact-SHA CI · main untouched")
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

        lines[seat.name] = "A1 solving three coordinated functions across two files…"
        render("Finalist provider " + (index + 1) + "/" + seats.size + " · attempt 1", false)
        val prompt = WorkspaceProviderCiBenchmark.initialPrompt(seedFailureSummary)
        val modelStarted = SystemClock.elapsedRealtime()
        val request = runCatching { seat.request(available.second, prompt) }.getOrElse {
            lines[seat.name] = "A1 REQUEST BLOCKED · " + (it.message ?: "local validation").take(100)
            runSeat(run, seats, index + 1)
            return
        }
        dispatch(
            run,
            seat.client,
            request,
            onFailure = {
                lines[seat.name] = "A1 MODEL ERROR · " + it.take(100)
                runSeat(run, seats, index + 1)
            },
            onResponse = { response ->
                val prepared = runCatching {
                    WorkspaceProviderCiBenchmark.prepare(seat.read(response))
                }.getOrElse {
                    lines[seat.name] = "A1 OUTPUT REJECTED · " +
                        (it.message ?: "invalid coding reply").take(100)
                    runSeat(run, seats, index + 1)
                    return@dispatch
                }
                val modelMs = SystemClock.elapsedRealtime() - modelStarted
                commitProvider(
                    run = run,
                    seats = seats,
                    index = index,
                    seat = seat,
                    key = available.second,
                    prepared = prepared,
                    modelMs = modelMs,
                    attempt = 1,
                    firstFailureRun = null,
                )
            },
        )
    }

    private fun commitProvider(
        run: Long,
        seats: List<Seat>,
        index: Int,
        seat: Seat,
        key: String,
        prepared: WorkspaceProviderCiBenchmark.Prepared,
        modelMs: Long,
        attempt: Int,
        firstFailureRun: Long?,
    ) {
        lines[seat.name] = "A" + attempt + " code accepted · GitHub preflight…"
        render("Provider " + (index + 1) + "/" + seats.size + " · attempt " + attempt, false)
        dispatch(
            run,
            WorkspaceGitHubConnector.client,
            WorkspaceGitHubConnector.writeAccessRequest(pairing),
            onFailure = {
                lines[seat.name] = "A" + attempt + " GITHUB PREFLIGHT ERROR · " + it.take(100)
                runSeat(run, seats, index + 1)
            },
            onResponse = { response ->
                val access = runCatching {
                    WorkspaceGitHubConnector.readWriteAccess(response)
                }.getOrElse {
                    lines[seat.name] = "A" + attempt + " GITHUB PREFLIGHT ERROR · " +
                        (it.message ?: "invalid response").take(100)
                    runSeat(run, seats, index + 1)
                    return@dispatch
                }
                val saved = requireNotNull(connection)
                require(access.repository.equals(saved.repository, ignoreCase = true) &&
                    access.branch == saved.branch && access.prBase == "main") {
                    "GitHub benchmark binding changed"
                }
                val message = if (attempt == 1) {
                    "test: finalist multi-file benchmark " + seat.slug
                } else {
                    "test: finalist multi-file repair " + seat.slug
                }
                val plan = WorkspaceGitHubWritePolicy.commitPlan(
                    expectedHead = access.headSha,
                    message = message,
                    files = WorkspaceProviderCiBenchmark.providerFiles(prepared),
                )
                dispatch(
                    run,
                    WorkspaceGitHubConnector.client,
                    WorkspaceGitHubConnector.commitRequest(pairing, plan),
                    onFailure = {
                        lines[seat.name] = "A" + attempt + " COMMIT ERROR · " + it.take(100)
                        runSeat(run, seats, index + 1)
                    },
                    onResponse = { commitResponse ->
                        val receipt = WorkspaceGitHubConnector.readCommitReceipt(commitResponse)
                        require(receipt.previousHead == access.headSha &&
                            receipt.branch == access.branch &&
                            receipt.files == WorkspaceProviderCiBenchmark.TARGET_PATHS) {
                            "Provider benchmark commit receipt mismatch"
                        }
                        wroteProviderCommit = true
                        lines[seat.name] =
                            "A" + attempt + " COMMITTED " + receipt.commitSha.take(12) +
                                " · waiting exact CI"
                        render("Provider attempt committed; watching exact GitHub Actions SHA.", false)
                        awaitCi(
                            run = run,
                            commitSha = receipt.commitSha,
                            label = seat.name + " A" + attempt,
                            onCompleted = { workflow, token ->
                                if (workflow.conclusion == "success") {
                                    lines[seat.name] = if (attempt == 1) {
                                        "PASS A1 · model " + modelMs + "ms · CI #" +
                                            workflow.runNumber + " GREEN"
                                    } else {
                                        "RECOVERED A2 · A1 CI #" + firstFailureRun +
                                            " FAILURE · A2 model " + modelMs + "ms · CI #" +
                                            workflow.runNumber + " GREEN"
                                    }
                                    runSeat(run, seats, index + 1)
                                } else if (attempt == 1) {
                                    lines[seat.name] = "A1 FAIL · model " + modelMs +
                                        "ms · CI #" + workflow.runNumber +
                                        " " + (workflow.conclusion ?: "unknown").uppercase() +
                                        " · capturing A2 evidence"
                                    render("First attempt failed; capturing bounded evidence for same-provider repair.", false)
                                    readFailureContext(
                                        run = run,
                                        token = token,
                                        workflow = workflow,
                                        onReady = { failureSummary ->
                                            retrySeat(
                                                run = run,
                                                seats = seats,
                                                index = index,
                                                seat = seat,
                                                key = key,
                                                previousSource = prepared.combinedSource(),
                                                failureSummary = failureSummary,
                                                firstFailureRun = workflow.runNumber,
                                            )
                                        },
                                        onFailure = {
                                            lines[seat.name] = "A1 FAIL · CI #" +
                                                workflow.runNumber + " · A2 CONTEXT ERROR · " +
                                                it.take(80)
                                            runSeat(run, seats, index + 1)
                                        },
                                    )
                                } else {
                                    lines[seat.name] = "FAIL A2 · A1 CI #" + firstFailureRun +
                                        " FAILURE · A2 model " + modelMs + "ms · CI #" +
                                        workflow.runNumber + " " +
                                        (workflow.conclusion ?: "unknown").uppercase()
                                    runSeat(run, seats, index + 1)
                                }
                            },
                            onFailure = {
                                lines[seat.name] = "A" + attempt + " CI WATCH ERROR · " + it.take(100)
                                runSeat(run, seats, index + 1)
                            },
                        )
                    },
                )
            },
        )
    }

    private fun retrySeat(
        run: Long,
        seats: List<Seat>,
        index: Int,
        seat: Seat,
        key: String,
        previousSource: String,
        failureSummary: String,
        firstFailureRun: Long,
    ) {
        lines[seat.name] = "A1 CI #" + firstFailureRun + " FAILURE · A2 repairing same task…"
        render("Same provider repair · " + (index + 1) + "/" + seats.size + " · attempt 2", false)
        val prompt = WorkspaceProviderCiBenchmark.retryPrompt(failureSummary, previousSource)
        val modelStarted = SystemClock.elapsedRealtime()
        val request = runCatching { seat.request(key, prompt) }.getOrElse {
            lines[seat.name] = "A1 CI #" + firstFailureRun +
                " FAILURE · A2 REQUEST BLOCKED · " +
                (it.message ?: "local validation").take(80)
            runSeat(run, seats, index + 1)
            return
        }
        dispatch(
            run,
            seat.client,
            request,
            onFailure = {
                lines[seat.name] = "A1 CI #" + firstFailureRun +
                    " FAILURE · A2 MODEL ERROR · " + it.take(80)
                runSeat(run, seats, index + 1)
            },
            onResponse = { response ->
                val prepared = runCatching {
                    WorkspaceProviderCiBenchmark.prepare(seat.read(response))
                }.getOrElse {
                    lines[seat.name] = "A1 CI #" + firstFailureRun +
                        " FAILURE · A2 OUTPUT REJECTED · " +
                        (it.message ?: "invalid coding reply").take(80)
                    runSeat(run, seats, index + 1)
                    return@dispatch
                }
                val modelMs = SystemClock.elapsedRealtime() - modelStarted
                commitProvider(
                    run = run,
                    seats = seats,
                    index = index,
                    seat = seat,
                    key = key,
                    prepared = prepared,
                    modelMs = modelMs,
                    attempt = 2,
                    firstFailureRun = firstFailureRun,
                )
            },
        )
    }

    private fun seedFailure(run: Long) {
        seedLine = "GitHub preflight…"
        render("Creating deliberate two-file failing fixture on protected feature branch.", false)
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
                    message = "test: seed finalist multi-file benchmark failure",
                    files = WorkspaceProviderCiBenchmark.failureFiles(),
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
                            receipt.files == WorkspaceProviderCiBenchmark.TARGET_PATHS) {
                            "Finalist multi-file benchmark seed receipt mismatch"
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
                                        render("Real failure captured. Starting Groq vs xKiro finalist attempts.", false)
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
                headSha = commitSha,
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
                    files = WorkspaceProviderCiBenchmark.baselineFiles(),
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
                                    "Finalist multi-file benchmark complete. Baseline " +
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
