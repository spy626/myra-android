package com.myra.assistant.service

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.ai.ApiKeyStore
import com.myra.assistant.ai.DeepResearchClient
import com.myra.assistant.ai.HandsFreeMediaGuard
import com.myra.assistant.model.AppCommand
import com.myra.assistant.phone.AppActionExecutor
import com.myra.assistant.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    private var waitingForFreshInputAfterCommand = false
    private var commandUserTextEmitted = false
    private var localCommandExecutedThisTurn = false
    private var lastCommandKey = ""
    private var lastCommandAt = 0L
    private var hideNextModelTranscript = false
    private var mediaBlockedTurn = false
    private var lastAnnouncementKey = ""
    private var lastAnnouncementAt = 0L
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mediaGuard by lazy { HandsFreeMediaGuard(this) }
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
        val key = ApiKeyStore(this).get(ApiKeyStore.GEMINI)
        val name = configuredUserName(p.getString("user_name", null))
        if (key.isBlank()) { emitState("Add your Gemini API key in Settings"); stopSelf(); return }
        audio = AudioEngine(this)
        val selectedVoice = p.getString("voice", "Aoede") ?: "Aoede"
        live = GeminiLiveClient(
            key, p.getString("model", "gemini-3.1-flash-live-preview")!!,
            selectedVoice, systemPrompt(name, p.getString("personality", "GF") ?: "GF", selectedVoice)
        ).also { client ->
            client.onState = { emitState(it) }
            client.onReady = { audio?.start(); listener?.onReady(); client.sendText("Greet $name briefly and naturally.") }
            client.onAudio = { if (!suppressModelForTurn && mediaGuard.allowModelResponse()) audio?.queueAudio(it) }
            client.onInputTranscript = inputTranscript@ { part ->
                when (mediaGuard.inspect(part)) {
                    HandsFreeMediaGuard.Gate.BLOCK -> {
                        commandProbe.append(part)
                        val directCommand = CommandParser.parseDirectMediaControl(commandProbe.toString())
                            ?: CommandParser.parseDirectMediaControl(part)
                            ?: CommandParser.parse(commandProbe.toString()).takeIf(::isSafeDirectMediaCommand)
                            ?: CommandParser.parse(part).takeIf(::isSafeDirectMediaCommand)
                        if (directCommand != null) {
                            val spoken = commandProbe.toString().trim()
                            if (spoken.isNotBlank() && !commandUserTextEmitted) {
                                listener?.onUserText(spoken)
                                commandUserTextEmitted = true
                            }
                            mediaBlockedTurn = false
                            executeCommand(directCommand)
                            return@inputTranscript
                        }
                        if (!mediaBlockedTurn) emitState("Media Guard active — direct search, close and WhatsApp commands are ready")
                        mediaBlockedTurn = true
                        output.clear()
                        return@inputTranscript
                    }
                    HandsFreeMediaGuard.Gate.WAKE_DETECTED -> {
                        mediaBlockedTurn = false
                        suppressModelForTurn = false
                        waitingForFreshInputAfterCommand = false
                        emitState("Listening — media lowered for 10 seconds")
                    }
                    HandsFreeMediaGuard.Gate.OPEN -> mediaBlockedTurn = false
                }
                // After a local phone command, delayed Gemini packets are discarded until
                // the server has completed that command turn and the user actually starts
                // speaking again. The first transcript of that new turn safely re-enables
                // normal model output.
                if (waitingForFreshInputAfterCommand) {
                    waitingForFreshInputAfterCommand = false
                    suppressModelForTurn = false
                    localCommandExecutedThisTurn = false
                }
                input.append(part); commandProbe.append(part)
                val command = CommandParser.parse(part) ?: CommandParser.parse(commandProbe.toString())
                // A streamed transcript may first contain only "YouTube" and later add
                // "mein search karo Lols Gaming". Never execute a plain open-app command
                // from an incomplete chunk; confirm it from the complete turn below.
                if (command != null && command !is AppCommand.OpenApp && command !is AppCommand.DeepResearch) {
                    val spoken = commandProbe.toString().trim()
                    if (spoken.isNotBlank() && !commandUserTextEmitted) {
                        listener?.onUserText(spoken)
                        commandUserTextEmitted = true
                    }
                    executeCommand(command)
                }
            }
            client.onOutputTranscript = { if (!suppressModelForTurn && !hideNextModelTranscript && mediaGuard.allowModelResponse()) output.append(it) }
            client.onTurnComplete = turnComplete@ {
                if (mediaBlockedTurn && !mediaGuard.isAwake()) {
                    mediaBlockedTurn = false
                    input.clear(); output.clear(); commandProbe.clear(); commandUserTextEmitted = false
                    localCommandExecutedThisTurn = false
                    return@turnComplete
                }
                if (hideNextModelTranscript) {
                    hideNextModelTranscript = false
                    input.clear(); output.clear(); commandProbe.clear(); commandUserTextEmitted = false
                    waitingForFreshInputAfterCommand = true
                    return@turnComplete
                }
                val userText = input.toString().trim(); val myraText = output.toString().trim()
                if (userText.isNotBlank() && !commandUserTextEmitted) listener?.onUserText(userText)
                // Run one final parse over the complete transcript. Partial Live transcript
                // chunks can omit or mistranscribe the action word even when the final text
                // contains enough context to identify the device command.
                if (!suppressModelForTurn && userText.isNotBlank()) {
                    CommandParser.parse(userText)?.let { executeCommand(it) }
                }
                if (myraText.isNotBlank() && !suppressModelForTurn) listener?.onMyraText(myraText)
                input.clear(); output.clear(); commandProbe.clear()
                commandUserTextEmitted = false
                if (suppressModelForTurn) waitingForFreshInputAfterCommand = true
                if (mediaGuard.isAwake()) mediaGuard.finishInteraction()
            }
            client.onError = { emitState(it) }
            audio?.onMicChunk = { client.sendAudio(it) }
            audio?.onAmplitude = { listener?.onAmplitude(it) }
            audio?.onSpeakingChanged = { listener?.onSpeaking(it); updateNotification(if (it) "MYRA is speaking" else "MYRA is listening") }
            client.connect()
        }
    }

    private fun isSafeDirectMediaCommand(command: AppCommand): Boolean = when (command) {
        is AppCommand.SearchYouTube, AppCommand.RepeatYouTubeSearch,
        is AppCommand.ReplyWhatsApp, AppCommand.QueryWhatsAppMessages -> true
        else -> false
    }

    private fun shouldExecute(command: AppCommand): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val key = when (command) {
            is AppCommand.OpenApp -> "open:${command.appName.lowercase(Locale.ROOT)}"
            is AppCommand.CloseCurrentApp -> "close:${command.requestedName.orEmpty().lowercase(Locale.ROOT)}"
            is AppCommand.SearchYouTube -> "youtube-search:${command.query.lowercase(Locale.ROOT)}"
            AppCommand.RepeatYouTubeSearch -> "youtube-search:repeat"
            is AppCommand.DeepResearch -> "research:${command.query.orEmpty().lowercase(Locale.ROOT)}"
            is AppCommand.ReplyWhatsApp -> "whatsapp-reply:${command.sender.orEmpty().lowercase(Locale.ROOT)}:${command.message.lowercase(Locale.ROOT)}"
            AppCommand.QueryWhatsAppMessages -> "whatsapp-message-query"
        }
        // One utterance can arrive as several slightly different Live transcript chunks.
        // A longer semantic dedupe window prevents the same local action/error appearing
        // repeatedly while still allowing an intentional command a few seconds later.
        if (key == lastCommandKey && now - lastCommandAt < 4_000L) return false
        lastCommandKey = key; lastCommandAt = now; return true
    }

    private fun executeCommand(command: AppCommand) {
        if (localCommandExecutedThisTurn || !shouldExecute(command)) return
        localCommandExecutedThisTurn = true
        if (command is AppCommand.DeepResearch) { executeDeepResearch(command); return }
        suppressModelForTurn = true
        waitingForFreshInputAfterCommand = true
        commandProbe.clear()
        output.clear()
        audio?.interrupt()
        live?.interrupt()
        mediaGuard.finishInteraction()
        val result = appActions.execute(command)
        listener?.onMyraText(result.message, !result.success)
        emitState(result.message)
        speakLocalResult(result.message)
    }

    private fun speakLocalResult(message: String) {
        hideNextModelTranscript = true
        suppressModelForTurn = false
        live?.sendText("Speak exactly this local Android result naturally. Do not add or change facts: $message")
    }

    private fun executeDeepResearch(command: AppCommand.DeepResearch) {
        val query = command.query?.trim().orEmpty()
        suppressModelForTurn = true; waitingForFreshInputAfterCommand = false; output.clear(); commandProbe.clear()
        audio?.interrupt(); live?.interrupt()
        if (query.isBlank()) {
            val prompt = "Haan, deep research kar sakti hoon. Kis topic par research chahiye?"
            listener?.onMyraText(prompt); emitState("Waiting for a research topic")
            speakResearchSummary(prompt)
            return
        }
        listener?.onMyraText("Researching “$query”…")
        emitState("Deep Research in progress…")
        val prefs = getSharedPreferences("myra", MODE_PRIVATE)
        val apiKey = ApiKeyStore(this).get(ApiKeyStore.TAVILY)
        val endpoint = prefs.getString("tavily_api_url", "https://api.tavily.com/search").orEmpty()
        val depth = prefs.getString("research_depth", "basic").orEmpty()
        serviceScope.launch {
            val result = DeepResearchClient().search(query, apiKey, endpoint, depth)
            listener?.onMyraText(result.report, !result.success)
            emitState(if (result.success) "Deep Research complete" else "Deep Research failed")
            if (result.success) speakResearchSummary(result.spokenSummary)
            else { suppressModelForTurn = false; waitingForFreshInputAfterCommand = true }
        }
    }

    private fun speakResearchSummary(summary: String) {
        hideNextModelTranscript = true
        suppressModelForTurn = false
        live?.sendText("Speak this research result aloud naturally and briefly. Do not add facts or mention URLs: $summary")
    }

    private fun systemPrompt(name: String, mode: String, voice: String): String {
        val style = when (mode) { "Professional" -> "Formal English, precise, no emoji, at most two sentences."; "Assistant" -> "Friendly Hinglish or English, balanced and helpful, at most three sentences."; else -> "Warm caring Hinglish companion, at most three sentences." }
        val femaleVoice = voice.lowercase(Locale.ROOT) in setOf("aoede", "kore", "leda", "zephyr")
        val genderStyle = if (femaleVoice) {
            "You have a female identity and the selected female voice is $voice. In Hindi and Hinglish always use feminine self-reference such as karungi, sakti hoon, sun rahi hoon, and gayi. Never say karunga, sakta hoon, sun raha hoon, or gaya about yourself."
        } else {
            "You have a male identity and the selected male voice is $voice. In Hindi and Hinglish use masculine self-reference consistently."
        }
        val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.getDefault()).format(Date())
        return "You are MYRA speaking ALOUD to $name. Current date/time: $now. $style $genderStyle Keep the same identity, voice character, and grammatical gender for the entire Live session, including after Android opens or closes another app. Android executes phone actions locally. When the user asks to open, launch, close, stop, search inside YouTube, check WhatsApp messages, or send/reply to a message, do not answer, invent a sender, or claim success; remain silent because Android reports the verified local result. Never invent notification, contact, or message information. Never claim a phone action succeeded yourself."
    }

    private fun configuredUserName(saved: String?): String =
        saved?.trim()?.takeIf { it.isNotBlank() && !it.equals("Friend", ignoreCase = true) } ?: "Zopy"

    private fun emitState(text: String) { listener?.onState(text); updateNotification(text) }

    private fun speakWhatsAppAnnouncement(sender: String, message: String?) {
        if (live == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        val key = "${sender.lowercase(Locale.ROOT)}|${message.orEmpty().lowercase(Locale.ROOT)}"
        if (key == lastAnnouncementKey && now - lastAnnouncementAt < 30_000L) return
        lastAnnouncementKey = key
        lastAnnouncementAt = now
        audio?.interrupt()
        mediaGuard.beginAssistantTurn()
        val name = configuredUserName(getSharedPreferences("myra", MODE_PRIVATE).getString("user_name", null))
        val announcement = if (message == null) {
            "$name, WhatsApp mein $sender ka private message aaya hai. Content sensitive hai, main aloud nahi padhungi. Kya reply doon?"
        } else {
            "$name, WhatsApp mein $sender ka message aaya hai: $message. Kya reply doon?"
        }
        live?.sendText("Speak this notification announcement naturally in Hinglish. Do not add anything: $announcement")
        emitState("WhatsApp message from $sender")
    }
    private fun createChannel() { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL_ID, "MYRA background voice", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, MyraVoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setColor(Color.rgb(255, 23, 68)).setContentTitle("MYRA background voice").setContentText(text)
            .setContentIntent(open).setOngoing(true).addAction(0, "Stop", stop).build()
    }
    private fun updateNotification(text: String) { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text)) }
    private fun stopSession() { serviceScope.cancel(); mediaGuard.release(); live?.disconnect(); audio?.release(); live = null; audio = null; isRunning = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
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
        fun startDeepResearch(query: String?) { instance?.executeCommand(AppCommand.DeepResearch(query)) }
        fun announceWhatsApp(sender: String, message: String?) { instance?.speakWhatsAppAnnouncement(sender, message) }
        fun interrupt() { instance?.audio?.interrupt(); instance?.live?.interrupt() }
    }
}
