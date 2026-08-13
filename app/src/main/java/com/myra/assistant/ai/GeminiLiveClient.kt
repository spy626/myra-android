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
    var onState: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val manualClose = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val client = OkHttpClient.Builder().pingInterval(8, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var renewThread: Thread? = null
    private val ready = AtomicBoolean(false)

    fun connect() {
        if (apiKey.isBlank()) { onError?.invoke("Add your Gemini API key in Settings"); return }
        manualClose.set(false)
        reconnecting.set(false)
        val attempt = generation.incrementAndGet()
        onState?.invoke("Checking Gemini access…")
        ready.set(false)
        val modelId = model.removePrefix("models/")
        val check = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$modelId").header("x-goog-api-key", apiKey).build()
        client.newCall(check).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (generation.get() == attempt && !manualClose.get()) onError?.invoke("Network check failed: ${e.message}")
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
        val setup = JSONObject().put("setup", JSONObject()
            .put("model", "models/${model.removePrefix("models/")}")
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
                .put("temperature", 0.9))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject()))
        webSocket.send(setup.toString())
        renewThread = Thread {
            try { Thread.sleep(540_000); if (!manualClose.get()) { socket?.close(1000, "Session renewal"); Thread.sleep(3_000); connect() } }
            catch (_: InterruptedException) { }
        }.also { it.start() }
    }

    fun sendAudio(bytes: ByteArray) {
        val audio = JSONObject().put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000")
        socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audio", audio)).toString())
    }

    fun sendText(text: String) {
        val turn = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))
        socket?.send(JSONObject().put("clientContent", JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", true)).toString())
    }

    fun interrupt() = socket?.send(JSONObject().put("clientContent", JSONObject().put("turns", JSONArray()).put("turnComplete", true)).toString())

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
            if (root.has("setupComplete")) { ready.set(true); onState?.invoke("Ready"); onReady?.invoke(); return }
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
        if (!manualClose.get() && reason.isNotBlank()) onError?.invoke("Gemini closed connection ($code): $reason")
    }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (socket !== webSocket) return
        ready.set(false); onState?.invoke("Disconnected")
        if (!manualClose.get()) scheduleReconnect()
    }
    private fun scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true) || manualClose.get()) return
        Thread {
            try { Thread.sleep(3_000); if (!manualClose.get()) connect() }
            finally { reconnecting.set(false) }
        }.start()
    }
    fun disconnect() { manualClose.set(true); generation.incrementAndGet(); renewThread?.interrupt(); socket?.close(1000, "App closed"); socket = null }
}
