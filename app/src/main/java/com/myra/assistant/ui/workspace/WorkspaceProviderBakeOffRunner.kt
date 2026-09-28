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

/**
 * Sequential synthetic bake-off. It never reads GitHub/project files, never writes code and never
 * changes provider routing. One click is consent to send only the fixed synthetic prompt.
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

    private val prefs = context.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
    private var generation = 0L
    private var active: Call? = null
    private val lines = mutableListOf<String>()
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
        lines.clear()
        listener.onUpdate(
            "Synthetic only · no GitHub/project source · no file writes.\nStarting…",
            false,
        )
        runSeat(generation, seats(), 0)
    }

    @Synchronized fun cancel() {
        generation += 1
        active?.cancel()
        active = null
    }

    private fun render(done: Boolean) {
        val header = "Synthetic only · no GitHub/project source · no file writes."
        listener.onUpdate((listOf(header) + lines).joinToString("\n"), done)
    }

    private fun runSeat(run: Long, seats: List<Seat>, index: Int) {
        if (index >= seats.size) {
            synchronized(this) {
                if (run == generation) active = null
            }
            render(done = true)
            return
        }
        val seat = seats[index]
        val availability = runCatching { seat.available() }.getOrDefault(Pair(false, ""))
        if (!availability.first) {
            lines += seat.name + " — SKIPPED (key/required Free opt-in unavailable)"
            render(done = false)
            runSeat(run, seats, index + 1)
            return
        }

        val request = runCatching { seat.request(availability.second) }.getOrElse { error ->
            lines += seat.name + " — REQUEST BLOCKED (" +
                (error.message ?: "local validation").take(120) + ")"
            render(done = false)
            runSeat(run, seats, index + 1)
            return
        }
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
                val ms = SystemClock.elapsedRealtime() - started
                lines += seat.name + " — ERROR · " + ms + "ms · network/preflight"
                render(done = false)
                runSeat(run, seats, index + 1)
            }

            override fun onResponse(call: Call, response: Response) {
                synchronized(this@WorkspaceProviderBakeOffRunner) {
                    if (run != generation || active !== call) {
                        response.close()
                        return
                    }
                    active = null
                }
                val ms = SystemClock.elapsedRealtime() - started
                val outcome = runCatching {
                    val text = seat.read(response)
                    WorkspaceProviderBakeOff.evaluate(text)
                }
                outcome.onSuccess { evaluation ->
                    val state = if (evaluation.passed) {
                        "PASS"
                    } else {
                        "PARTIAL " + evaluation.correct + "/" + evaluation.total +
                            if (!evaluation.strictJson) " · format" else ""
                    }
                    lines += seat.name + " — " + state + " · " + ms + "ms"
                }.onFailure { error ->
                    lines += seat.name + " — ERROR · " + ms + "ms · " +
                        (error.message ?: "provider response rejected").take(120)
                }
                render(done = false)
                runSeat(run, seats, index + 1)
            }
        })
    }
}
