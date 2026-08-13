package com.myra.assistant.ai

import android.util.Base64
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private val client = OkHttpClient.Builder().pingInterval(8, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var renewThread: Thread? = null

    fun connect() {
        if (apiKey.isBlank()) { onError?.invoke("Add your Gemini API key in Settings"); return }
        manualClose.set(false)
        onState?.invoke("Connecting…")
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        socket = client.newWebSocket(Request.Builder().url(url).build(), this)
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
            if (root.has("setupComplete")) { onState?.invoke("Ready"); onReady?.invoke(); return }
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

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        onError?.invoke(t.message ?: "Gemini connection failed")
        if (!manualClose.get()) Thread { Thread.sleep(3_000); connect() }.start()
    }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { onState?.invoke("Disconnected") }
    fun disconnect() { manualClose.set(true); renewThread?.interrupt(); socket?.close(1000, "App closed"); client.dispatcher.executorService.shutdown() }
}
