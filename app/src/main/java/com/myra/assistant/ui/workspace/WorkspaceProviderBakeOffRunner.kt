package com.myra.assistant.ui.workspace

import android.content.Context
import android.os.SystemClock
import com.myra.assistant.ai.ApiKeyStore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.math.roundToLong

/**
 * Three-round sequential synthetic bake-off. It never reads GitHub/project files, never writes
 * code and never changes provider routing. One click sends only the fixed synthetic prompt.
 */
internal class WorkspaceProviderBakeOffRunner(
    context: Context,
    private val keys: ApiKeyStore,
    private val listener: Listener,
) {
    interface Listener {
        fun onUpdate(text: String, done: Boolean)
    }

    private data class Seat(
        val name: String,
        val available: () -> Pair<Boolean, String>,
        val request: (String) -> Request,
        val client: OkHttpClient,
        val read: (Response) -> String,
    )

    private data class Stats(
        var attempts: Int = 0,
        var perfect: Int = 0,
        var strictJson: Int = 0,
        var t1: Int = 0,
        var t2: Int = 0,
        var t3: Int = 0,
        var errors: Int = 0,
        val latencyMs: MutableList<Long> = mutableListOf(),
    )

    companion object {
        private const val ROUNDS = 3
    }

    private val prefs = context.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
    private var generation = 0L
    private var active: Call? = null
    private val stats = linkedMapOf<String, Stats>()
    private val skipped = linkedSetOf<String>()
    private val blocked = linkedSetOf<String>()
    private var currentLabel = "Starting…"

    val isRunning: Boolean get() = synchronized(this) { active != null }

    private val message = WorkspaceConversationStore.Message(
        "provider-bake-off",
        "user",
        WorkspaceProviderBakeOff.PROMPT,
        0L,
    )

    private fun firstKey(slot: String): String =
        runCatching { keys.get(slot) }.getOrDefault("")
            .split(',').asSequence().map(String::trim).firstOrNull(String::isNotBlank).orEmpty()

    private fun seats(): List<Seat> = listOf(
        Seat(
            name = "OpenRouter · openrouter/free",
            available = {
                val key = firstKey(ApiKeyStore.OPENROUTER)
                Pair(WorkspaceCodingAutoFallback.validKey(key), key)
            },
            request = { key ->
                WorkspaceChatGateway.request(
                    WorkspaceChatGateway.Provider.OPENROUTER_FREE,
                    key,
                    listOf(message),
                )
            },
            client = WorkspaceFreeAiSuggestion.client,
            read = WorkspaceFreeAiSuggestion::readResponse,
        ),
        Seat(
            name = "Groq · " + WorkspaceGroqFree.MODEL,
            available = {
                val key = firstKey(ApiKeyStore.GROQ)
                val approved = prefs.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false)
                Pair(approved && WorkspaceCodingAutoFallback.validKey(key), key)
            },
            request = { key -> WorkspaceGroqFree.request(key, listOf(message)) },
            client = WorkspaceFreeAiSuggestion.client,
            read = WorkspaceGroqFree::read,
        ),
        Seat(
            name = "LLM7 · " + WorkspaceLlm7Free.MODEL,
            available = {
                val key = firstKey(ApiKeyStore.LLM7)
                val approved = prefs.getBoolean(WorkspaceLlm7Free.PREFERENCE_KEY, false)
                Pair(approved && WorkspaceLlm7Free.validKey(key), key)
            },
            request = { key -> WorkspaceLlm7Free.request(key, listOf(message)) },
            client = WorkspaceLlm7Free.client,
            read = WorkspaceLlm7Free::read,
        ),
        Seat(
            name = "xKiro · " + WorkspaceXKiroFree.MODEL,
            available = {
                val key = firstKey(ApiKeyStore.XKIRO)
                Pair(WorkspaceXKiroFree.validKey(key), key)
            },
            request = { key ->
                WorkspaceXKiroFree.deliberationRequest(key, WorkspaceProviderBakeOff.PROMPT)
            },
            client = WorkspaceXKiroFree.deliberationClient,
            read = WorkspaceXKiroFree::readDeliberation,
        ),
        Seat(
            name = "Z.ai · " + WorkspaceZaiFree.DEFAULT_TEXT_MODEL,
            available = {
                val key = firstKey(ApiKeyStore.ZAI)
                val approved = prefs.getBoolean(WorkspaceZaiFree.CODING_PREFERENCE_KEY, false)
                Pair(approved && WorkspaceZaiFree.validKey(key), key)
            },
            request = { key ->
                WorkspaceZaiFree.deliberationRequest(
                    key = key,
                    prompt = WorkspaceProviderBakeOff.PROMPT,
                    textModel = WorkspaceZaiFree.DEFAULT_TEXT_MODEL,
                    sourceIncluded = false,
                    sourceApproved = false,
                )
            },
            client = WorkspaceZaiFree.client,
            read = WorkspaceZaiFree::read,
        ),
    )

    @Synchronized fun start() {
        if (active != null) return
        generation += 1
        stats.clear()
        skipped.clear()
        blocked.clear()
        currentLabel = "Starting round 1/" + ROUNDS + "…"
        val seats = seats()
        seats.forEach { stats[it.name] = Stats() }
        render(done = false)
        runSeat(generation, seats, round = 1, index = 0)
    }

    @Synchronized fun cancel() {
        generation += 1
        active?.cancel()
        active = null
    }

    private fun summaryLine(name: String): String {
        if (name in skipped) return name + " — SKIPPED (key/required Free opt-in unavailable)"
        if (name in blocked) return name + " — BLOCKED by local/provider validation"
        val s = stats.getValue(name)
        if (s.attempts == 0) return name + " — waiting"
        val avg = if (s.latencyMs.isEmpty()) 0L else s.latencyMs.average().roundToLong()
        return name + " — perfect " + s.perfect + "/" + s.attempts + " · " +
            "T1 " + s.t1 + "/" + s.attempts + " T2 " + s.t2 + "/" + s.attempts +
            " T3 " + s.t3 + "/" + s.attempts + " · JSON " + s.strictJson + "/" +
            s.attempts + " · avg " + avg + "ms" +
            if (s.errors > 0) " · errors " + s.errors else ""
    }

    private fun render(done: Boolean) {
        val header = "3 rounds · synthetic only · no GitHub/project source · no file writes."
        val names = stats.keys.toList()
        val body = if (done) {
            listOf(header, "FINAL RESULTS") + names.map(::summaryLine)
        } else {
            listOf(header, currentLabel) + names.map(::summaryLine)
        }
        listener.onUpdate(body.joinToString("\n"), done)
    }

    private fun next(run: Long, seats: List<Seat>, round: Int, index: Int) {
        if (index + 1 < seats.size) {
            runSeat(run, seats, round, index + 1)
        } else if (round < ROUNDS) {
            runSeat(run, seats, round + 1, 0)
        } else {
            synchronized(this) {
                if (run == generation) active = null
            }
            currentLabel = "Completed " + ROUNDS + " rounds."
            render(done = true)
        }
    }

    private fun runSeat(run: Long, seats: List<Seat>, round: Int, index: Int) {
        val seat = seats[index]
        if (seat.name in skipped || seat.name in blocked) {
            next(run, seats, round, index)
            return
        }

        currentLabel = "Round " + round + "/" + ROUNDS + " · testing " + seat.name
        render(done = false)

        val availability = runCatching { seat.available() }.getOrDefault(Pair(false, ""))
        if (!availability.first) {
            skipped += seat.name
            render(done = false)
            next(run, seats, round, index)
            return
        }

        val request = runCatching { seat.request(availability.second) }.getOrElse {
            blocked += seat.name
            render(done = false)
            next(run, seats, round, index)
            return
        }

        val s = stats.getValue(seat.name)
        s.attempts += 1
        val started = SystemClock.elapsedRealtime()
        val call = seat.client.newCall(request)
        synchronized(this) {
            if (run != generation) return
            active = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@WorkspaceProviderBakeOffRunner) {
                    if (run != generation || active !== call) return
                    active = null
                }
                s.errors += 1
                s.latencyMs += SystemClock.elapsedRealtime() - started
                render(done = false)
                next(run, seats, round, index)
            }

            override fun onResponse(call: Call, response: Response) {
                synchronized(this@WorkspaceProviderBakeOffRunner) {
                    if (run != generation || active !== call) {
                        response.close()
                        return
                    }
                    active = null
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                s.latencyMs += elapsed
                val outcome = runCatching {
                    WorkspaceProviderBakeOff.evaluate(seat.read(response))
                }
                outcome.onSuccess { evaluation ->
                    if (evaluation.passed) s.perfect += 1
                    if (evaluation.strictJson) s.strictJson += 1
                    if (evaluation.t1) s.t1 += 1
                    if (evaluation.t2) s.t2 += 1
                    if (evaluation.t3) s.t3 += 1
                }.onFailure {
                    s.errors += 1
                }
                render(done = false)
                next(run, seats, round, index)
            }
        })
    }
}
