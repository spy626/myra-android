package com.myra.assistant.service

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.model.AppCommand
import com.myra.assistant.phone.AppActionExecutor
import com.myra.assistant.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.*

class MyraVoiceService : Service() {
    interface Listener {
        fun onState(text: String)
        fun onReady()
        fun onAmplitude(value: Float)
        fun onSpeaking(speaking: Boolean)
        fun onUserText(text: String)
        fun onMyraText(text: String, error: Boolean = false)
    }

    private var audio: AudioEngine? = null
    private var live: GeminiLiveClient? = null
    private val input = StringBuilder()
    private val output = StringBuilder()
    private val commandProbe = StringBuilder()
    private var suppressModelForTurn = false
    private var lastCommandKey = ""
    private var lastCommandAt = 0L
    private val appActions by lazy { AppActionExecutor(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting MYRA…"))
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_MUTE -> audio?.setMuted(intent.getBooleanExtra(EXTRA_MUTED, false))
            else -> if (live == null) connect()
        }
        return START_NOT_STICKY
    }

    private fun connect() {
        val p = getSharedPreferences("myra", MODE_PRIVATE)
        val key = p.getString("api_key", "").orEmpty()
        val name = p.getString("user_name", "Friend") ?: "Friend"
        if (key.isBlank()) { emitState("Add your Gemini API key in Settings"); stopSelf(); return }
        audio = AudioEngine(this)
        live = GeminiLiveClient(
            key, p.getString("model", "gemini-3.1-flash-live-preview")!!,
            p.getString("voice", "Aoede")!!, systemPrompt(name, p.getString("personality", "GF") ?: "GF")
        ).also { client ->
            client.onState = { emitState(it) }
            client.onReady = { audio?.start(); listener?.onReady(); client.sendText("Greet $name briefly and naturally.") }
            client.onAudio = { if (!suppressModelForTurn) audio?.queueAudio(it) }
            client.onInputTranscript = { part ->
                input.append(part); commandProbe.append(part)
                val command = CommandParser.parse(part) ?: CommandParser.parse(commandProbe.toString())
                if (command != null && shouldExecute(command)) {
                    suppressModelForTurn = true; commandProbe.clear(); audio?.interrupt()
                    val result = appActions.execute(command)
                    listener?.onMyraText(result.message, !result.success)
                    emitState(result.message)
                }
            }
            client.onOutputTranscript = { if (!suppressModelForTurn) output.append(it) }
            client.onTurnComplete = {
                val userText = input.toString().trim(); val myraText = output.toString().trim()
                if (userText.isNotBlank()) listener?.onUserText(userText)
                if (myraText.isNotBlank() && !suppressModelForTurn) listener?.onMyraText(myraText)
                input.clear(); output.clear(); commandProbe.clear(); suppressModelForTurn = false
            }
            client.onError = { emitState(it) }
            audio?.onMicChunk = { client.sendAudio(it) }
            audio?.onAmplitude = { listener?.onAmplitude(it) }
            audio?.onSpeakingChanged = { listener?.onSpeaking(it); updateNotification(if (it) "MYRA is speaking" else "MYRA is listening") }
            client.connect()
        }
    }

    private fun shouldExecute(command: AppCommand): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val key = when (command) {
            is AppCommand.OpenApp -> "open:${command.appName.lowercase(Locale.ROOT)}"
            is AppCommand.CloseCurrentApp -> "close:${command.requestedName.orEmpty().lowercase(Locale.ROOT)}"
        }
        if (key == lastCommandKey && now - lastCommandAt < 2_000L) return false
        lastCommandKey = key; lastCommandAt = now; return true
    }

    private fun systemPrompt(name: String, mode: String): String {
        val style = when (mode) { "Professional" -> "Formal English, precise, no emoji, at most two sentences."; "Assistant" -> "Friendly Hinglish or English, balanced and helpful, at most three sentences."; else -> "Warm caring Hinglish companion, at most three sentences." }
        val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.getDefault()).format(Date())
        return "You are MYRA speaking ALOUD to $name. Current date/time: $now. $style Never claim a phone action succeeded; Android reports action results."
    }

    private fun emitState(text: String) { listener?.onState(text); updateNotification(text) }
    private fun createChannel() { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL_ID, "MYRA background voice", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, MyraVoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setColor(Color.rgb(255, 23, 68)).setContentTitle("MYRA background voice").setContentText(text)
            .setContentIntent(open).setOngoing(true).addAction(0, "Stop", stop).build()
    }
    private fun updateNotification(text: String) { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text)) }
    private fun stopSession() { live?.disconnect(); audio?.release(); live = null; audio = null; isRunning = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { instance = null; if (isRunning) stopSession(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.myra.START_VOICE"
        const val ACTION_STOP = "com.myra.STOP_VOICE"
        const val ACTION_MUTE = "com.myra.MUTE_VOICE"
        const val EXTRA_MUTED = "muted"
        private const val CHANNEL_ID = "myra_voice"
        private const val NOTIFICATION_ID = 1001
        @Volatile var isRunning = false
        @Volatile var listener: Listener? = null
        @Volatile private var instance: MyraVoiceService? = null
        fun sendText(text: String) { instance?.live?.sendText(text) }
        fun interrupt() { instance?.audio?.interrupt(); instance?.live?.interrupt() }
    }
}
