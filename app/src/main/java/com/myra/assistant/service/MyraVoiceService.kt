package com.myra.assistant.service

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.ai.ApiKeyStore
import com.myra.assistant.ai.DeepResearchClient
import com.myra.assistant.ai.HandsFreeMediaGuard
import com.myra.assistant.model.AppCommand
import com.myra.assistant.phone.AppActionExecutor
import com.myra.assistant.MyApplication
import com.myra.assistant.commands.CommandParser as StructuredCommandParser
import com.myra.assistant.ui.main.MainActivity
import com.myra.assistant.voice.VoiceResponseFormatter
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
    private var probableActionTurn = false
    private var pendingLocalSpeech: String? = null
    private var validatingLocalSpeech: String? = null
    private var localSpeechValidationToken = 0L
    private var localSpeechValidationAttempt = 0
    private var localSpeechHasContent = false
    private var allowUntranscribedLocalSpeech = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val localSpeechAudio = mutableListOf<ByteArray>()
    private val localSpeechTranscript = StringBuilder()
    private var localPlaybackActive = false
    private var localSpeechStreamedDirectly = false
    private var localSpeechGenerationComplete = false
    private var localAudioSpeaking = false
    private var pendingActionAfterLocalSpeech: (() -> Unit)? = null
    private var lastLocalSpeechKey = ""
    private var lastLocalSpeechAt = 0L
    private var lastAnnouncementKey = ""
    private var lastAnnouncementAt = 0L
    private var hasGreeted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mediaGuard by lazy { HandsFreeMediaGuard(this) }
    private val appActions by lazy { AppActionExecutor(this) }
    private val assistantController by lazy { (application as MyApplication).assistantController }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting LYRA…"))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LYRA:BackgroundVoice")
            .apply { setReferenceCounted(false); acquire() }
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_MUTE -> audio?.setMuted(intent.getBooleanExtra(EXTRA_MUTED, false))
            else -> if (live == null) connect()
        }
        return START_STICKY
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
            client.onReady = {
                audio?.start()
                listener?.onReady()
                if (!hasGreeted) {
                    hasGreeted = true
                    client.sendText("Greet $name briefly and naturally.")
                } else {
                    emitState("LYRA reconnected — listening")
                }
            }
            client.onAudio = {
                if (validatingLocalSpeech != null) {
                    localSpeechHasContent = true
                    if (allowUntranscribedLocalSpeech) {
                        // These words were selected locally for an already-successful,
                        // non-sensitive action. Stream the selected Gemini voice as soon
                        // as it arrives instead of buffering the entire sentence.
                        localSpeechStreamedDirectly = true
                        localPlaybackActive = true
                        audio?.queueAudio(it)
                    } else {
                        localSpeechAudio += it.copyOf()
                    }
                }
                else if (!suppressModelForTurn && mediaGuard.allowModelResponse()) audio?.queueAudio(it)
            }
            client.onInputTranscript = inputTranscript@ { part ->
                when (mediaGuard.inspect(part)) {
                    HandsFreeMediaGuard.Gate.BLOCK -> {
                        appendTranscript(commandProbe, part)
                        var directCommand = CommandParser.parseDirectMediaControl(commandProbe.toString())
                            ?: CommandParser.parseDirectMediaControl(part)
                            ?: CommandParser.parse(commandProbe.toString())?.takeIf(::isSafeDirectMediaCommand)
                            ?: CommandParser.parse(part)?.takeIf(::isSafeDirectMediaCommand)
                        if (directCommand is AppCommand.OpenApp &&
                            !CommandParser.isExplicitOpenCommand(commandProbe.toString()) &&
                            !CommandParser.isExplicitOpenCommand(part)
                        ) {
                            directCommand = null
                        }
                        if (directCommand != null) {
                            // Media Guard runs before the normal fresh-input reset below.
                            // A genuine direct command heard during playback starts a new
                            // user turn, so release the completed previous command here.
                            // shouldExecute() still blocks duplicate transcript chunks.
                            if (waitingForFreshInputAfterCommand) {
                                waitingForFreshInputAfterCommand = false
                                localCommandExecutedThisTurn = false
                                commandUserTextEmitted = false
                            }
                            val spoken = commandProbe.toString().trim()
                            if (spoken.isNotBlank() && !commandUserTextEmitted) {
                                listener?.onUserText(spoken)
                                commandUserTextEmitted = true
                            }
                            mediaBlockedTurn = false
                            executeCommand(directCommand)
                            return@inputTranscript
                        }
                        if (CommandParser.isProbableDeviceAction(part) || CommandParser.isProbableDeviceAction(commandProbe.toString())) {
                            probableActionTurn = true
                            suppressModelForTurn = true
                            output.clear()
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
                appendTranscript(input, part); appendTranscript(commandProbe, part)
                val command = CommandParser.parse(part) ?: CommandParser.parse(commandProbe.toString())
                if (CommandParser.isProbableDeviceAction(part) || CommandParser.isProbableDeviceAction(commandProbe.toString())) {
                    probableActionTurn = true
                    suppressModelForTurn = true
                    output.clear()
                }
                // A streamed transcript may first contain only "YouTube" and later add
                // "mein search karo Lols Gaming". Never execute a plain open-app command
                // from an incomplete chunk; confirm it from the complete turn below.
                val explicitOpen = command is AppCommand.OpenApp && CommandParser.isExplicitOpenCommand(part)
                if (command != null && (command !is AppCommand.OpenApp || explicitOpen) && command !is AppCommand.DeepResearch) {
                    val spoken = commandProbe.toString().trim()
                    if (spoken.isNotBlank() && !commandUserTextEmitted) {
                        listener?.onUserText(spoken)
                        commandUserTextEmitted = true
                    }
                    executeCommand(command)
                }
            }
            client.onOutputTranscript = {
                if (validatingLocalSpeech != null) {
                    localSpeechHasContent = true
                    appendTranscript(localSpeechTranscript, it)
                }
                else if (!suppressModelForTurn && !hideNextModelTranscript && mediaGuard.allowModelResponse()) appendTranscript(output, it)
            }
            client.onTurnComplete = turnComplete@ {
                if (validatingLocalSpeech != null) {
                    // Sending clientContent interrupts the previous Gemini generation.
                    // Its interrupted turnComplete can arrive before the new confirmation.
                    // Ignore that empty boundary, and briefly allow the independently
                    // streamed output transcript to arrive after the audio turn completes.
                    if (localSpeechHasContent) {
                        val token = localSpeechValidationToken
                        mainHandler.postDelayed({
                            if (token == localSpeechValidationToken && validatingLocalSpeech != null) {
                                finishValidatedLocalSpeech()
                                resetTurnBuffers()
                                waitingForFreshInputAfterCommand = true
                            }
                        }, 350L)
                    }
                    return@turnComplete
                }
                pendingLocalSpeech?.let { message ->
                    pendingLocalSpeech = null
                    resetTurnBuffers()
                    beginValidatedLocalSpeech(message)
                    return@turnComplete
                }
                if (mediaBlockedTurn && !mediaGuard.isAwake()) {
                    mediaBlockedTurn = false
                    input.clear(); output.clear(); commandProbe.clear(); commandUserTextEmitted = false
                    localCommandExecutedThisTurn = false
                    probableActionTurn = false
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
                if (userText.isNotBlank() && !localCommandExecutedThisTurn) {
                    val parsed = CommandParser.parse(userText)
                    if (parsed != null) {
                        executeCommand(parsed)
                    } else if (probableActionTurn || CommandParser.isProbableDeviceAction(userText)) {
                        suppressModelForTurn = true
                        val error = if (CommandParser.isAmbiguousFlashlightCommand(userText)) {
                            "Zopy, torch on karun ya off?"
                        } else {
                            "Zopy, command samajh aayi, lekin action clear nahi hua. Ek baar seedha bolkar try karo."
                        }
                        listener?.onMyraText(error, true)
                        emitState(error)
                        queueLocalSpeech(error)
                    }
                }
                if (myraText.isNotBlank() && !suppressModelForTurn) listener?.onMyraText(myraText)
                input.clear(); output.clear(); commandProbe.clear()
                commandUserTextEmitted = false
                probableActionTurn = false
                if (suppressModelForTurn) waitingForFreshInputAfterCommand = true
                if (mediaGuard.isAwake()) mediaGuard.finishInteraction()
                pendingLocalSpeech?.let { message ->
                    pendingLocalSpeech = null
                    beginValidatedLocalSpeech(message)
                }
            }
            client.onError = { emitState(it) }
            audio?.onMicChunk = { client.sendAudio(it) }
            audio?.onAmplitude = { listener?.onAmplitude(it) }
            audio?.onSpeakingChanged = { speaking ->
                localAudioSpeaking = speaking
                listener?.onSpeaking(speaking)
                updateNotification(if (speaking) "LYRA is speaking" else "LYRA is listening")
                if (!speaking && localPlaybackActive && localSpeechGenerationComplete) {
                    finishLocalPlayback()
                }
            }
            client.connect()
        }
    }

    private fun isSafeDirectMediaCommand(command: AppCommand): Boolean = when (command) {
        is AppCommand.SearchYouTube, AppCommand.RepeatYouTubeSearch,
        is AppCommand.OpenApp, is AppCommand.CloseCurrentApp,
        is AppCommand.ReplyWhatsApp, AppCommand.QueryWhatsAppMessages,
        AppCommand.GoHome, AppCommand.GoBack, AppCommand.CurrentTime,
        AppCommand.BatteryLevel, is AppCommand.SetFlashlight -> true
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
            AppCommand.GoHome -> "go-home"
            AppCommand.GoBack -> "go-back"
            AppCommand.CurrentTime -> "current-time"
            AppCommand.BatteryLevel -> "battery-level"
            is AppCommand.SetFlashlight -> "flashlight:${command.enabled}"
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
        if (command is AppCommand.CloseCurrentApp &&
            AccessibilityHelperService.instance != null &&
            AccessibilityHelperService.isEnabled(this)
        ) {
            prepareCloseAfterSpeech(command)
            return
        }
        mediaGuard.finishInteraction()
        val result = assistantController.processCommand(
            StructuredCommandParser.fromLegacy(command, command.toString()),
            speak = false,
            notifyListeners = false
        )
        listener?.onMyraText(result.spokenMessage, !result.success)
        emitState(result.spokenMessage)
        queueLocalSpeech(
            result.spokenMessage,
            allowUntranscribedAudio = result.success && isSafeUntranscribedConfirmation(command)
        )
    }

    private fun prepareCloseAfterSpeech(command: AppCommand.CloseCurrentApp) {
        val preferences = getSharedPreferences("myra", MODE_PRIVATE)
        val name = configuredUserName(preferences.getString("user_name", null))
        val personality = preferences.getString("personality", "GF") ?: "GF"
        val message = VoiceResponseFormatter.closeStarting(command.requestedName, personality, name)
        pendingActionAfterLocalSpeech = {
            val result = assistantController.processCommand(
                StructuredCommandParser.fromLegacy(command, command.toString()),
                speak = false,
                notifyListeners = false
            )
            if (result.success) {
                audio?.setMuted(false)
                emitState("Sun rahi hoon…")
            } else {
                listener?.onMyraText(result.spokenMessage, true)
                emitState(result.spokenMessage)
                queueLocalSpeech(result.spokenMessage)
            }
        }
        listener?.onMyraText(message)
        emitState(message)
        mediaGuard.beginAssistantTurn()
        queueLocalSpeech(message, allowUntranscribedAudio = true)
    }

    private fun runPendingActionAfterSpeech(): Boolean {
        val action = pendingActionAfterLocalSpeech ?: return false
        pendingActionAfterLocalSpeech = null
        mainHandler.post { action() }
        return true
    }

    private fun isSafeUntranscribedConfirmation(command: AppCommand): Boolean = when (command) {
        is AppCommand.OpenApp, is AppCommand.CloseCurrentApp,
        is AppCommand.SearchYouTube, AppCommand.RepeatYouTubeSearch,
        AppCommand.GoHome, AppCommand.GoBack, AppCommand.CurrentTime,
        AppCommand.BatteryLevel, is AppCommand.SetFlashlight -> true
        is AppCommand.ReplyWhatsApp, AppCommand.QueryWhatsAppMessages,
        is AppCommand.DeepResearch -> false
    }

    private fun appendTranscript(builder: StringBuilder, part: String) {
        val clean = part.trim()
        if (clean.isBlank()) return
        if (builder.isNotEmpty() && !builder.last().isWhitespace()) builder.append(' ')
        builder.append(clean)
    }

    private fun queueLocalSpeech(message: String, allowUntranscribedAudio: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        val key = normalizeSpeech(message)
        if (key == lastLocalSpeechKey && now - lastLocalSpeechAt < 4_000L) return
        lastLocalSpeechKey = key
        lastLocalSpeechAt = now
        suppressModelForTurn = true
        audio?.setMuted(true)
        allowUntranscribedLocalSpeech = allowUntranscribedAudio
        if (validatingLocalSpeech == null) beginValidatedLocalSpeech(message)
        else pendingLocalSpeech = message
    }

    private fun beginValidatedLocalSpeech(message: String, retry: Boolean = false) {
        val client = live
        if (client == null) {
            fallbackLocalSpeech(message)
            return
        }
        if (!retry) localSpeechValidationAttempt = 0
        localSpeechValidationAttempt++
        localSpeechValidationToken++
        val token = localSpeechValidationToken
        validatingLocalSpeech = message
        localSpeechHasContent = false
        localSpeechStreamedDirectly = false
        localSpeechGenerationComplete = false
        localSpeechAudio.clear()
        localSpeechTranscript.clear()
        suppressModelForTurn = true
        client.sendText("Say exactly these words once, with the selected natural voice. Do not add, remove, translate, explain, or introduce them: ${org.json.JSONObject.quote(message)}")
        mainHandler.postDelayed({
            if (token == localSpeechValidationToken && validatingLocalSpeech != null) {
                finishValidatedLocalSpeech()
            }
        }, 8_000L)
    }

    private fun finishValidatedLocalSpeech() {
        val expected = validatingLocalSpeech ?: return
        val actual = localSpeechTranscript.toString()
        validatingLocalSpeech = null
        if (localSpeechStreamedDirectly) {
            localSpeechGenerationComplete = true
            localSpeechAudio.clear()
            localSpeechTranscript.clear()
            if (!localAudioSpeaking && localPlaybackActive) finishLocalPlayback()
            return
        }
        val transcriptMatches = normalizeSpeech(actual) == normalizeSpeech(expected)
        // Gemini Live documents that output transcription is independent from audio
        // and may be delayed or absent. For already-successful, non-sensitive local
        // actions we may play the requested natural-voice audio when no transcript was
        // returned. Messaging and failed-action confirmations remain strict.
        val safeAudioWithoutTranscript = allowUntranscribedLocalSpeech &&
            actual.isBlank() && localSpeechAudio.isNotEmpty()
        if ((transcriptMatches || safeAudioWithoutTranscript) && localSpeechAudio.isNotEmpty()) {
            localSpeechGenerationComplete = true
            localPlaybackActive = true
            localSpeechAudio.forEach { audio?.queueAudio(it) }
            localSpeechAudio.clear()
            localSpeechTranscript.clear()
        } else {
            localSpeechAudio.clear()
            localSpeechTranscript.clear()
            if (localSpeechValidationAttempt < 2 && live != null) {
                beginValidatedLocalSpeech(expected, retry = true)
            } else {
                fallbackLocalSpeech(expected)
            }
        }
    }

    private fun finishLocalPlayback() {
        localPlaybackActive = false
        localSpeechStreamedDirectly = false
        localSpeechGenerationComplete = false
        if (!runPendingActionAfterSpeech()) {
            audio?.setMuted(false)
            emitState("Sun rahi hoon…")
        }
    }

    private fun fallbackLocalSpeech(message: String) {
        assistantController.speakMessage(message) {
            if (!runPendingActionAfterSpeech()) {
                audio?.setMuted(false)
                emitState("Sun rahi hoon…")
            }
        }
    }

    private fun resetTurnBuffers() {
        input.clear()
        output.clear()
        commandProbe.clear()
        commandUserTextEmitted = false
        probableActionTurn = false
        mediaBlockedTurn = false
    }

    private fun normalizeSpeech(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

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
        return "You are LYRA speaking ALOUD to $name. Current date/time: $now. $style $genderStyle Keep the same identity, voice character, and grammatical gender for the entire Live session, including after Android opens or closes another app. Android executes phone actions locally. For every request to open, launch, close, stop, search, play, pause, go home/back, report device time or battery, control the flashlight, check WhatsApp, or send/reply to a message: produce no audio and no confirmation. Android reports the deterministic local result. Never invent device state, notification, contact, message, delivery, or successful phone action."
    }

    private fun configuredUserName(saved: String?): String =
        saved?.trim()?.takeIf { it.isNotBlank() && !it.equals("Friend", ignoreCase = true) } ?: "Zopy"

    private fun executeTypedLocalCommand(text: String): Boolean {
        val command = CommandParser.parse(text) ?: return false
        localCommandExecutedThisTurn = false
        waitingForFreshInputAfterCommand = false
        executeCommand(command)
        pendingLocalSpeech?.let { message ->
            pendingLocalSpeech = null
            beginValidatedLocalSpeech(message)
        }
        return true
    }

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
    private fun createChannel() { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL_ID, "LYRA background voice", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, MyraVoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setColor(Color.rgb(255, 23, 68)).setContentTitle("LYRA background voice").setContentText(text)
            .setContentIntent(open).setOngoing(true).addAction(0, "Stop", stop).build()
    }
    private fun updateNotification(text: String) { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text)) }
    private fun stopSession() { serviceScope.cancel(); mediaGuard.release(); live?.disconnect(); audio?.release(); wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null; live = null; audio = null; isRunning = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
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
        fun executeLocalText(text: String): Boolean = instance?.executeTypedLocalCommand(text) == true
        fun startDeepResearch(query: String?) { instance?.executeCommand(AppCommand.DeepResearch(query)) }
        fun announceWhatsApp(sender: String, message: String?) { instance?.speakWhatsAppAnnouncement(sender, message) }
        fun interrupt() { instance?.audio?.interrupt(); instance?.live?.interrupt() }
    }
}
