package com.myra.assistant.ui.workspace

import android.content.Context
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
        val request: (String) -> Request,
        val client: OkHttpClient,
        val read: (Response) -> String,
    )

    private val prefs = context.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
    private val store = WorkspaceConnectorCredentialStore(context)
    private val lines = linkedMapOf<String, String>()
    private var generation = 0L
    private var active: Call? = null
    private var running = false
    private var pairing = ""
    private var connection: WorkspaceConnectorCredentialStore.GitHubConnection? = null
    private var wroteProviderCommit = false

    val isRunning: Boolean get() = synchronized(this) { running }

    private val message = WorkspaceConversationStore.Message(
        "provider-real-ci-benchmark",
        "user",
        WorkspaceProviderCiBenchmark.PROMPT,
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
            { key -> WorkspaceFreeAiSuggestion.request(key, WorkspaceProviderCiBenchmark.PROMPT) },
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
            { key -> WorkspaceGroqFree.request(key, listOf(message)) },
            WorkspaceFreeAiSuggestion.client,
            WorkspaceGroqFree::read,
        ),
        Seat(
            "LLM7 · " + WorkspaceLlm7Free.MODEL,
            "llm7",
            {
                val key = firstKey(ApiKeyStore.LLM7)
                Pair(
                    prefs.getBoolean(WorkspaceLlm7Free.PREFERENCE_KEY, false) &&
                        WorkspaceLlm7Free.validKey(key),
                    key,
                )
            },
            { key -> WorkspaceLlm7Free.request(key, listOf(message)) },
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
            { key -> WorkspaceXKiroFree.deliberationRequest(key, WorkspaceProviderCiBenchmark.PROMPT) },
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
            { key ->
                WorkspaceZaiFree.deliberationRequest(
                    key = key,
                    prompt = WorkspaceProviderCiBenchmark.PROMPT,
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
        lines.clear()
        seats().forEach { lines[it.name] = "waiting" }
        render("Starting real compile/test benchmark…", done = false)
        runSeat(generation, seats(), 0)
    }

    @Synchronized fun cancel() {
        generation += 1
        active?.cancel()
        active = null
        running = false
    }

    private fun render(label: String, done: Boolean) {
        val body = buildList {
            add("REAL CI · synthetic source only · protected feature branch · main untouched")
            add(label)
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

        lines[seat.name] = "asking model…"
        render("Provider " + (index + 1) + "/" + seats.size, false)
        val request = runCatching { seat.request(available.second) }.getOrElse {
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
                commitProvider(run, seats, index, seat, prepared.source)
            },
        )
    }

    private fun commitProvider(
        run: Long,
        seats: List<Seat>,
        index: Int,
        seat: Seat,
        source: String,
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
                            "COMMITTED " + receipt.commitSha.take(12) + " · CI pending"
                        render("Provider commit created; moving to next provider.", false)
                        runSeat(run, seats, index + 1)
                    },
                )
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
                        finish(
                            run,
                            "Provider commits created. Baseline restored at " +
                                receipt.commitSha.take(12) +
                                ". GitHub Actions now decides pass/fail.",
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
            running = false
        }
        render(message, done = true)
    }
}
