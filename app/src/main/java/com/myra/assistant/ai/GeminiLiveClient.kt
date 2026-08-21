package com.myra.assistant.ai

import android.util.Base64
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GeminiLiveClient(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val systemPrompt: String
) : WebSocketListener() {
    var onReady: (() -> Unit)? = null
    var onAudio: ((ByteArray) -> Unit)? = null
    var onInputTranscript: ((String) -> Unit)? = null
    var onOutputTranscript: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onToolCall: ((String, String, JSONObject) -> Unit)? = null
    var onState: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val manualClose = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val reconnectAttempt = AtomicInteger(0)
    private val client = OkHttpClient.Builder().pingInterval(8, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var renewThread: Thread? = null
    private val ready = AtomicBoolean(false)

    fun connect() {
        if (apiKey.isBlank()) { onError?.invoke("Add your Gemini API key in Settings"); return }
        manualClose.set(false)
        val attempt = generation.incrementAndGet()
        onState?.invoke("Checking Gemini access…")
        ready.set(false)
        val modelId = model.removePrefix("models/")
        val check = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$modelId").header("x-goog-api-key", apiKey).build()
        client.newCall(check).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (generation.get() == attempt && !manualClose.get()) {
                    onState?.invoke("Network unavailable — reconnecting…")
                    reconnecting.set(false)
                    scheduleReconnect()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (generation.get() != attempt || manualClose.get()) return
                    if (!it.isSuccessful) {
                        val raw = it.body?.string().orEmpty()
                        val message = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                        onError?.invoke("Gemini access error (${it.code}): ${message ?: it.message}")
                        return
                    }
                    openLiveSocket(attempt)
                }
            }
        })
    }

    private fun openLiveSocket(attempt: Int) {
        onState?.invoke("Connecting…")
        val encodedKey = java.net.URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$encodedKey"
        val opened = try {
            client.newWebSocket(Request.Builder().url(url).build(), this)
        } catch (e: Exception) {
            onError?.invoke("Could not start Gemini Live: ${e.message}")
            return
        }
        socket = opened
        Thread {
            Thread.sleep(15_000)
            if (generation.get() == attempt && socket === opened && !ready.get() && !manualClose.get()) {
                onError?.invoke("Connection timed out. Check API key, internet, and Gemini Live access.")
                opened.cancel()
            }
        }.start()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (socket !== webSocket || manualClose.get()) {
            webSocket.close(1000, "Stale connection")
            return
        }
        val setup = JSONObject().put("setup", JSONObject()
            .put("model", "models/${model.removePrefix("models/")}")
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
                .put("temperature", 0.9))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(
                JSONObject()
                    .put("name", "perform_phone_action")
                    .put("description", "Perform one allowed Android phone action when the user's natural-language intent is clear. Ask a conversational follow-up instead of calling this tool when the action, app, direction, recipient, or query is uncertain. Instagram Reels must use REQUEST_INSTAGRAM_REELS so Android asks for confirmation.")
                    .put("parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put("properties", JSONObject()
                            .put("action", JSONObject()
                                .put("type", "STRING")
                                .put("enum", JSONArray(listOf(
                                    "OPEN_APP", "CLOSE_APP", "YOUTUBE_SEARCH", "PLAY_YOUTUBE",
                                    "OPEN_YOUTUBE_SHORTS", "REQUEST_INSTAGRAM_REELS",
                                    "SCROLL_DOWN", "SCROLL_UP", "SCROLL_REPEAT",
                                    "MEDIA_PAUSE", "MEDIA_PLAY", "MEDIA_NEXT", "MEDIA_PREVIOUS", "MEDIA_FIRST",
                                    "FLASHLIGHT_ON", "FLASHLIGHT_OFF", "HOME", "BACK",
                                    "TIME", "BATTERY", "LIST_FEATURES", "QUERY_WHATSAPP"
                                )))
                                .put("description", "The single allowed action to perform."))
                            .put("target", JSONObject().put("type", "STRING").put("description", "App name for OPEN_APP or CLOSE_APP."))
                            .put("query", JSONObject().put("type", "STRING").put("description", "Search or media query for YOUTUBE_SEARCH or PLAY_YOUTUBE.")))
                        .put("required", JSONArray().put("action")))
            )))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject()))
        webSocket.send(setup.toString())
        renewThread = Thread {
            try {
                Thread.sleep(540_000)
                if (!manualClose.get() && socket === webSocket) webSocket.close(1000, "Session renewal")
            } catch (_: InterruptedException) { }
        }.also { it.start() }
    }

    fun sendAudio(bytes: ByteArray) {
        if (!ready.get() || bytes.isEmpty()) return
        val audio = JSONObject().put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000")
        sendWhenReady(JSONObject().put("realtimeInput", JSONObject().put("audio", audio)).toString())
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        val turn = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))
        sendWhenReady(JSONObject().put("clientContent", JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", true)).toString())
    }

    fun sendToolResponse(id: String, name: String, success: Boolean, message: String) {
        val response = JSONObject()
            .put("result", if (success) "success" else "failed")
            .put("message", message)
        val functionResponse = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("response", response)
        sendWhenReady(
            JSONObject().put(
                "toolResponse",
                JSONObject().put("functionResponses", JSONArray().put(functionResponse))
            ).toString()
        )
    }

    private fun sendWhenReady(payload: String): Boolean {
        val active = socket
        if (!ready.get() || active == null) {
            onState?.invoke("Reconnecting…")
            scheduleReconnect()
            return false
        }
        val accepted = active.send(payload)
        if (!accepted) {
            ready.set(false)
            onState?.invoke("Reconnecting…")
            scheduleReconnect()
        }
        return accepted
    }

    /**
     * Local playback is interrupted by AudioEngine. Never send an empty clientContent
     * turn: Gemini rejects that packet with close code 1007 (invalid argument).
     */
    fun interrupt() = Unit

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val root = JSONObject(text)
            root.optJSONObject("error")?.let {
                val message = it.optString("message", "Gemini rejected the Live setup")
                onError?.invoke("Gemini Live error: $message")
                manualClose.set(true)
                webSocket.close(1002, "Setup rejected")
                return
            }
            if (root.has("setupComplete")) {
                ready.set(true)
                reconnectAttempt.set(0)
                reconnecting.set(false)
                onState?.invoke("Ready")
                onReady?.invoke()
                return
            }
            root.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
                for (i in 0 until calls.length()) {
                    val call = calls.optJSONObject(i) ?: continue
                    val id = call.optString("id")
                    val name = call.optString("name")
                    if (id.isNotBlank() && name.isNotBlank()) {
                        onToolCall?.invoke(id, name, call.optJSONObject("args") ?: JSONObject())
                    }
                }
                return
            }
            val content = root.optJSONObject("serverContent") ?: return
            val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) for (i in 0 until parts.length()) {
                val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                if (!data.isNullOrBlank()) onAudio?.invoke(Base64.decode(data, Base64.DEFAULT))
            }
            content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { onInputTranscript?.invoke(it) }
            content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { onOutputTranscript?.invoke(it) }
            if (content.optBoolean("turnComplete")) onTurnComplete?.invoke()
        } catch (e: Exception) { onError?.invoke("Invalid Live response: ${e.message}") }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        onMessage(webSocket, bytes.utf8())
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (socket !== webSocket || manualClose.get()) return
        ready.set(false)
        val detail = response?.let { "HTTP ${it.code} ${it.message}" } ?: (t.message ?: "Network failure")
        onError?.invoke("Gemini connection failed: $detail")
        scheduleReconnect()
    }
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        ready.set(false)
        if (!manualClose.get() && code != 1000 && reason.isNotBlank()) {
            onError?.invoke("Gemini connection interrupted. Reconnecting…")
        }
    }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (socket !== webSocket) return
        ready.set(false)
        if (!manualClose.get()) {
            onState?.invoke("Reconnecting…")
            scheduleReconnect()
        }
    }
    private fun scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true) || manualClose.get()) return
        val attempt = reconnectAttempt.incrementAndGet().coerceAtMost(5)
        val delayMs = (3_000L * attempt).coerceAtMost(15_000L)
        Thread {
            try {
                Thread.sleep(delayMs)
                if (!manualClose.get()) {
                    reconnecting.set(false)
                    connect()
                }
            } catch (_: InterruptedException) {
                reconnecting.set(false)
            }
        }.start()
    }
    fun disconnect() { manualClose.set(true); generation.incrementAndGet(); renewThread?.interrupt(); socket?.close(1000, "App closed"); socket = null }
}
