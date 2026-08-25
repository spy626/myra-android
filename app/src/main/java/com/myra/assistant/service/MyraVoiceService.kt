package com.myra.assistant.service

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Build
import android.icu.text.Transliterator
import androidx.core.app.NotificationCompat
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.ai.ApiKeyStore
import com.myra.assistant.ai.DeepResearchClient
import com.myra.assistant.ai.HandsFreeMediaGuard
import com.myra.assistant.ai.LyraPlaybackCapturePolicy
import com.myra.assistant.ai.LiveTranscriptAssembler
import com.myra.assistant.data.memory.AutomaticMemoryChange
import com.myra.assistant.data.memory.AutomaticMemoryChangeParser
import com.myra.assistant.data.memory.ContextualRelationshipMemoryExtractor
import com.myra.assistant.data.memory.LyraMemoryDatabase
import com.myra.assistant.data.memory.MemoryCommand
import com.myra.assistant.data.memory.MemoryCommandParser
import com.myra.assistant.data.memory.MemoryConfirmationDecision
import com.myra.assistant.data.memory.MemoryConfirmationParser
import com.myra.assistant.data.memory.MemoryCandidate
import com.myra.assistant.data.memory.PersonalMemoryExtractor
import com.myra.assistant.data.memory.PersonalMemoryContextCorrection
import com.myra.assistant.data.memory.PersonalMemoryPermissionPrompt
import com.myra.assistant.data.memory.PersonalMemoryRecallFormatter
import com.myra.assistant.data.memory.MemoryRepository
import com.myra.assistant.data.memory.MemoryRelationshipPolicy
import com.myra.assistant.data.memory.SavedMemoryContextFormatter
import com.myra.assistant.data.memory.MemoryWriteResult
import com.myra.assistant.data.memory.MemorySafetyPolicy
import com.myra.assistant.data.memory.MemorySaveDecision
import com.myra.assistant.data.memory.SemanticMemoryProposalValidator
import com.myra.assistant.model.AppCommand
import com.myra.assistant.phone.AppActionExecutor
import com.myra.assistant.MyApplication
import com.myra.assistant.commands.CommandParser as StructuredCommandParser
import com.myra.assistant.ui.main.MainActivity
import com.myra.assistant.voice.LocalSpeechGate
import com.myra.assistant.voice.RomanHinglishFormatter
import com.myra.assistant.voice.VoiceResponseFormatter
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal object FriendConversationPolicy {
    const val REPLY_DISCIPLINE =
        "Default to one short natural sentence for ordinary conversation; use a second only when needed. " +
            "Use more only when Zopy explicitly asks for detail or the topic requires a safety explanation. " +
            "Answer complete questions directly and stop—never append a closing question, topic prompt, or 'aur sunao'. " +
            "Ask no follow-up unless missing information prevents a useful answer; " +
            "if you ask one, it must be the only question in the entire reply. " +
            "Never use customer-support wording such as 'help kar sakti hoon', and never sound dismissive with " +
            "phrases such as 'isse zyada main kya boloon' or pressure the user to give a specific topic."

    const val MALE_USER_GRAMMAR =
        "Zopy is male, so when addressing him use masculine forms such as sakte ho, karoge, and gaye; " +
            "never address him as sakti ho or karogi."
}

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
    private var connectionPreparing = false
    private val input = StringBuilder()
    private val output = StringBuilder()
    private val commandProbe = StringBuilder()
    private var lastUserIntentText = ""
    private val recentRelationshipTurns = mutableListOf<Pair<Long, String>>()
    private var suppressModelForTurn = false
    private var waitingForFreshInputAfterCommand = false
    private var commandUserTextEmitted = false
    private var localCommandExecutedThisTurn = false
    private var ambiguousMessageTurn = false
    private var incompleteActionFragmentTurn = false
    private var lastCommandKey = ""
    private var hasAcknowledgedScrollDirection = false
    private var lastScrollDirection = AppCommand.ScrollDirection.DOWN
    private var lastCommandAt = 0L
    private var hideNextModelTranscript = false
    private var mediaBlockedTurn = false
    private var probableActionTurn = false
    private var pendingLocalSpeech: String? = null
    private var pendingLocalSpeechPolicy = LocalSpeechValidationPolicy.DEFAULT
    private var pendingLocalSpeechAllowsSilence = false
    private var validatingLocalSpeech: String? = null
    private var localSpeechValidationToken = 0L
    private var localSpeechValidationAttempt = 0
    private var localSpeechValidationPolicy = LocalSpeechValidationPolicy.DEFAULT
    private var localSpeechHasContent = false
    private var allowUntranscribedLocalSpeech = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingMemoryCommand: MemoryCommand? = null
    private val memoryCommandRunnable = Runnable {
        val command = pendingMemoryCommand
        pendingMemoryCommand = null
        if (command != null && !localCommandExecutedThisTurn) {
            val spoken = commandProbe.toString().trim()
            if (spoken.isNotBlank() && !commandUserTextEmitted) {
                listener?.onUserText(romanDisplayText(spoken))
                commandUserTextEmitted = true
            }
            handleMemoryCommand(command)
        }
    }
    private var pendingDetectedPersonalMemory: MemoryCandidate? = null
    private val personalMemoryPauseRunnable = Runnable {
        val candidate = pendingDetectedPersonalMemory
        pendingDetectedPersonalMemory = null
        if (candidate != null && pendingPersonalMemory == null && !localCommandExecutedThisTurn) {
            val spoken = commandProbe.toString().trim()
            if (spoken.isNotBlank() && !commandUserTextEmitted) {
                listener?.onUserText(romanDisplayText(spoken))
                commandUserTextEmitted = true
            }
            requestPersonalMemoryPermission(candidate)
            resetTurnBuffers()
            waitingForFreshInputAfterCommand = true
        }
    }
    private var microphoneMuted = false
    private var deepResearchActive = false
    private var idleNudgeCount = 0
    private val idleNudgeRunnable = Runnable { handleIdleNudge() }
    private val localSpeechAudio = mutableListOf<ByteArray>()
    private val localSpeechTranscript = StringBuilder()
    private var localPlaybackActive = false
    private var localSpeechStreamedDirectly = false
    private var localSpeechGenerationComplete = false
    private var localAudioSpeaking = false
    private var pendingActionAfterLocalSpeech: (() -> Unit)? = null
    private var pendingConfirmedCommand: AppCommand? = null
    private var pendingConfirmationExpiresAt = 0L
    private var pendingPersonalMemory: MemoryCandidate? = null
    private var pendingPersonalMemoryExpiresAt = 0L
    private val pendingPersonalMemoryConfirmationInput = StringBuilder()
    private var lastLocalSpeechKey = ""
    private var lastLocalSpeechAt = 0L
    private var lastAnnouncementKey = ""
    private var lastAnnouncementAt = 0L
    private var hasGreeted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mediaGuard by lazy { HandsFreeMediaGuard(this) }
    private val romanTransliterator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Keep pronunciation marks long enough for RomanHinglishFormatter to
            // distinguish names such as करीम instead of flattening them to "karima".
            runCatching { Transliterator.getInstance("Any-Latin") }.getOrNull()
        } else null
    }
    private val appActions by lazy { AppActionExecutor(this) }
    private val memoryRepository by lazy { MemoryRepository(LyraMemoryDatabase.get(this).memoryDao()) }
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
            ACTION_MUTE -> {
                microphoneMuted = intent.getBooleanExtra(EXTRA_MUTED, false)
                audio?.setMuted(microphoneMuted)
                if (microphoneMuted) mainHandler.removeCallbacks(idleNudgeRunnable) else markUserInteraction()
            }
            else -> if (live == null) connect()
        }
        return START_STICKY
    }

    private fun connect() {
        if (connectionPreparing || live != null) return
        connectionPreparing = true
        serviceScope.launch {
            val savedMemoryContext = runCatching { buildSavedMemoryContext() }.getOrDefault("")
            mainHandler.post {
                connectionPreparing = false
                if (isRunning && live == null) connectLive(savedMemoryContext)
            }
        }
    }

    private fun connectLive(savedMemoryContext: String) {
        val p = getSharedPreferences("myra", MODE_PRIVATE)
        val key = ApiKeyStore(this).get(ApiKeyStore.GEMINI)
        val name = configuredUserName(p.getString("user_name", null))
        if (key.isBlank()) { emitState("Add your Gemini API key in Settings"); stopSelf(); return }
        audio = AudioEngine(this)
        val selectedVoice = p.getString("voice", "Aoede") ?: "Aoede"
        live = GeminiLiveClient(
            key, p.getString("model", "gemini-3.1-flash-live-preview")!!,
            selectedVoice,
            systemPrompt(name, p.getString("personality", "GF") ?: "GF", selectedVoice) +
                savedMemoryContext
        ).also { client ->
            client.onState = { emitState(it) }
            client.onReady = {
                isNaturalVoiceReady = true
                audio?.start()
                listener?.onReady()
                if (!hasGreeted) {
                    hasGreeted = true
                    client.sendText("Greet $name briefly and naturally.")
                } else {
                    emitState("LYRA reconnected — listening")
                }
                markUserInteraction()
            }
            client.onToolCall = { id, functionName, args ->
                mainHandler.post { handleSemanticToolCall(id, functionName, args) }
            }
            client.onAudio = {
                if (validatingLocalSpeech != null) {
                    localSpeechHasContent = true
                    if (localSpeechStreamedDirectly) {
                        // The transcript prefix already matched the prepared response.
                        // Continue streaming the remaining natural voice without waiting
                        // for the complete sentence.
                        audio?.queueAudio(it)
                    } else {
                        localSpeechAudio += it.copyOf()
                        startLocalSpeechWhenPrefixMatches()
                    }
                }
                else if (LyraPlaybackCapturePolicy.shouldAcceptModelAudio(
                        suppressed = suppressModelForTurn,
                        assistantAlreadySpeaking = localAudioSpeaking,
                        mediaGuardAllowsResponse = mediaGuard.allowModelResponse()
                    )
                ) {
                    // Capturable LYRA speech uses USAGE_MEDIA. Once the first valid
                    // chunk is accepted, keep Media Guard awake so LYRA never mistakes
                    // her own active AudioTrack for external YouTube playback.
                    mediaGuard.beginAssistantTurn()
                    audio?.queueAudio(it)
                }
            }
            client.onInputTranscript = inputTranscript@ { part ->
                if (handlePendingPersonalMemoryPermission(part)) return@inputTranscript
                if (handlePendingConfirmation(part)) return@inputTranscript
                if (isPhantomTranscript(part)) {
                    // Short echo/noise fragments must never become chat bubbles or
                    // receive a conversational answer.
                    suppressModelForTurn = true
                    output.clear()
                    return@inputTranscript
                }
                markUserInteraction()
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
                                listener?.onUserText(romanDisplayText(spoken))
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
                lastUserIntentText = input.toString().trim()
                val currentTranscript = commandProbe.toString().trim()
                val detectedPersonalMemory =
                    PersonalMemoryExtractor.extract(romanDisplayText(currentTranscript))
                if (detectedPersonalMemory != null) {
                    if (MemorySafetyPolicy.decide(detectedPersonalMemory) == MemorySaveDecision.AUTO_SAVE) {
                        // Clear, ordinary personal facts are learned silently at turn
                        // completion so LYRA's natural conversational reply continues.
                        pendingDetectedPersonalMemory = null
                        mainHandler.removeCallbacks(personalMemoryPauseRunnable)
                        return@inputTranscript
                    }
                    // A short pause lets streamed ASR finish the fact, then Android can
                    // ask permission without waiting for Gemini's full turn boundary.
                    suppressModelForTurn = true
                    output.clear()
                    audio?.interrupt()
                    pendingDetectedPersonalMemory = detectedPersonalMemory
                    mainHandler.removeCallbacks(personalMemoryPauseRunnable)
                    mainHandler.postDelayed(
                        personalMemoryPauseRunnable,
                        PERSONAL_MEMORY_PAUSE_MS
                    )
                } else if (pendingDetectedPersonalMemory != null) {
                    // A later chunk changed the sentence into something that is no
                    // longer a complete durable fact. Do not prompt from stale text.
                    pendingDetectedPersonalMemory = null
                    mainHandler.removeCallbacks(personalMemoryPauseRunnable)
                }
                if (CommandParser.isLikelyIncompleteActionFragment(currentTranscript)) {
                    incompleteActionFragmentTurn = true
                    suppressModelForTurn = true
                    output.clear()
                    audio?.interrupt()
                    return@inputTranscript
                } else if (incompleteActionFragmentTurn) {
                    // A later chunk completed the same thought, so resume the normal
                    // parser. If Gemini finalized the fragment as its own turn, the
                    // turn-complete guard below discards it without a chat bubble.
                    incompleteActionFragmentTurn = false
                    suppressModelForTurn = false
                }
                val romanMemoryTranscript = romanDisplayText(commandProbe.toString())
                if (MemoryCommandParser.looksLikeIntent(romanMemoryTranscript)) {
                    suppressModelForTurn = true
                    output.clear()
                    audio?.interrupt()
                    MemoryCommandParser.parse(romanMemoryTranscript)?.let { memoryCommand ->
                        pendingMemoryCommand = memoryCommand
                        mainHandler.removeCallbacks(memoryCommandRunnable)
                        mainHandler.postDelayed(memoryCommandRunnable, MEMORY_COMMAND_PAUSE_MS)
                    }
                }
                val ambiguousMessage = CommandParser.isAmbiguousMessageReference(commandProbe.toString())
                if (ambiguousMessage) {
                    ambiguousMessageTurn = true
                    suppressModelForTurn = true
                    output.clear()
                    audio?.interrupt()
                } else if (ambiguousMessageTurn && !MemoryCommandParser.looksLikeIntent(romanMemoryTranscript)) {
                    // A later transcript chunk completed the thought. Gemini already
                    // received the audio, so allow its contextual response again.
                    ambiguousMessageTurn = false
                    suppressModelForTurn = false
                }
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
                        listener?.onUserText(romanDisplayText(spoken))
                        commandUserTextEmitted = true
                    }
                    executeCommand(command)
                }
            }
            client.onOutputTranscript = {
                if (validatingLocalSpeech != null) {
                    localSpeechHasContent = true
                    appendTranscript(localSpeechTranscript, it)
                    startLocalSpeechWhenPrefixMatches()
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
                    localSpeechValidationPolicy = pendingLocalSpeechPolicy
                    allowUntranscribedLocalSpeech = pendingLocalSpeechAllowsSilence
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
                mainHandler.removeCallbacks(memoryCommandRunnable)
                pendingMemoryCommand = null
                mainHandler.removeCallbacks(personalMemoryPauseRunnable)
                pendingDetectedPersonalMemory = null
                val userText = input.toString().trim(); val myraText = output.toString().trim()
                if (incompleteActionFragmentTurn &&
                    CommandParser.isLikelyIncompleteActionFragment(userText)
                ) {
                    // Do not expose or answer partial ASR words such as "Tem",
                    // "tain", or "meses". The next completed utterance starts fresh.
                    audio?.interrupt()
                    resetTurnBuffers()
                    suppressModelForTurn = true
                    waitingForFreshInputAfterCommand = true
                    return@turnComplete
                }
                if (userText.isNotBlank() && !commandUserTextEmitted) listener?.onUserText(romanDisplayText(userText))
                // Run one final parse over the complete transcript. Partial Live transcript
                // chunks can omit or mistranscribe the action word even when the final text
                // contains enough context to identify the device command.
                if (userText.isNotBlank() && !localCommandExecutedThisTurn) {
                    if (CommandParser.isAmbiguousMessageReference(userText)) {
                        val clarification = "Message ke baare mein baat kar rahe ho, ya kisi ko bhejna hai?"
                        localCommandExecutedThisTurn = true
                        listener?.onMyraText(clarification)
                        emitState(clarification)
                        queueLocalSpeech(clarification, allowUntranscribedAudio = true)
                        resetTurnBuffers()
                        ambiguousMessageTurn = false
                        waitingForFreshInputAfterCommand = true
                        return@turnComplete
                    }
                    val memoryCommand = MemoryCommandParser.parse(romanDisplayText(userText))
                    if (memoryCommand != null) {
                        handleMemoryCommand(memoryCommand)
                        resetTurnBuffers()
                        waitingForFreshInputAfterCommand = true
                        return@turnComplete
                    }
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
                if (userText.isNotBlank() && !localCommandExecutedThisTurn) {
                    val displayUserText = romanDisplayText(userText)
                    val personalCandidate = PersonalMemoryExtractor.extract(displayUserText)
                        ?: contextualRelationshipCandidate(displayUserText)
                    if (personalCandidate != null) {
                        recentRelationshipTurns.clear()
                        if (MemorySafetyPolicy.decide(personalCandidate) == MemorySaveDecision.AUTO_SAVE) {
                            serviceScope.launch { memoryRepository.save(personalCandidate) }
                        } else {
                            requestPersonalMemoryPermission(personalCandidate)
                            resetTurnBuffers()
                            waitingForFreshInputAfterCommand = true
                            return@turnComplete
                        }
                    }
                    rememberRecentRelationshipTurn(displayUserText)
                    learnSafePreferenceFromCompletedTurn(userText)
                }
                if (myraText.isNotBlank() && !suppressModelForTurn) listener?.onMyraText(romanDisplayText(myraText))
                input.clear(); output.clear(); commandProbe.clear()
                commandUserTextEmitted = false
                probableActionTurn = false
                if (suppressModelForTurn) waitingForFreshInputAfterCommand = true
                if (mediaGuard.isAwake()) mediaGuard.finishInteraction()
                pendingLocalSpeech?.let { message ->
                    pendingLocalSpeech = null
                    localSpeechValidationPolicy = pendingLocalSpeechPolicy
                    allowUntranscribedLocalSpeech = pendingLocalSpeechAllowsSilence
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

    private suspend fun buildSavedMemoryContext(): String {
        memoryRepository.reconcileUniqueRelationships()
        return SavedMemoryContextFormatter.format(
            memoryRepository.relevant("", 8).map { it.fact }
        )
    }

    private fun learnSafePreferenceFromCompletedTurn(userText: String) {
        val romanUserText = romanDisplayText(userText)
        val change = AutomaticMemoryChangeParser.parse(romanUserText) ?: return
        serviceScope.launch {
            // Automatic learning stays silent. Only explicit remember/forget
            // commands produce a confirmation in the conversation.
            when (change) {
                is AutomaticMemoryChange.Save -> memoryRepository.save(change.candidate)
                is AutomaticMemoryChange.Forget -> memoryRepository.forgetStableKey(change.stableKey)
            }
        }
    }

    private fun handleExplicitMemoryText(text: String): Boolean {
        val command = MemoryCommandParser.parse(text) ?: return false
        handleMemoryCommand(command)
        return true
    }

    private fun handleMemoryCommand(command: MemoryCommand) {
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        output.clear()
        serviceScope.launch {
            val rememberResult = if (command is MemoryCommand.Remember) {
                memoryRepository.save(command.candidate)
            } else null
            if (command is MemoryCommand.Remember && rememberResult == MemoryWriteResult.NeedsPermission) {
                // An explicit remember command can still conflict with a unique
                // relationship. Enter the real confirmation flow so "haan" replaces
                // the old person instead of returning a dead-end generic question.
                mainHandler.post { requestPersonalMemoryPermission(command.candidate) }
                return@launch
            }
            val response = when (command) {
                is MemoryCommand.Remember -> when (rememberResult) {
                    is MemoryWriteResult.Saved -> "Got it, I'll remember that ${command.displayFact}."
                    is MemoryWriteResult.Rejected -> "I can't save passwords, security codes, or unsafe private information."
                    MemoryWriteResult.NeedsPermission, null -> "Save karne ki permission clear nahi hui."
                }
                is MemoryCommand.Read -> {
                    val memories = memoryRepository.relevant(command.query, 5)
                    PersonalMemoryRecallFormatter.format(memories.map { it.fact })
                }
                is MemoryCommand.Forget -> {
                    if (memoryRepository.forgetMatching(command.query)) "Okay, I forgot that."
                    else "I couldn't find that in my saved memories."
                }
            }
            mainHandler.post {
                listener?.onMyraText(response)
                emitState(response)
                queueLocalSpeech(
                    response,
                    allowUntranscribedAudio = true,
                    validationPolicy = LocalSpeechValidationPolicy.MEMORY
                )
            }
        }
    }

    private fun requestPersonalMemoryPermission(candidate: MemoryCandidate) {
        pendingPersonalMemory = null
        pendingPersonalMemoryConfirmationInput.clear()
        pendingPersonalMemoryExpiresAt = 0L
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        output.clear()
        // A correction can arrive while the previous memory prompt is still being
        // validated. Replace that prompt instead of leaving the new one queued behind
        // an interrupted Gemini turn that may never emit another turnComplete.
        cancelSpeechForNewAction()
        live?.interrupt()
        serviceScope.launch {
            val alreadySaved = memoryRepository.isAlreadySaved(candidate)
            val conflict = memoryRepository.uniqueRelationshipConflict(candidate)
            mainHandler.post {
                val message = if (alreadySaved) {
                    "Haan, mujhe yaad hai."
                } else if (conflict != null && MemoryRelationshipPolicy.isBestFriend(candidate)) {
                    val oldName = MemoryRelationshipPolicy.personName(conflict.fact) ?: "koi aur"
                    val newName = MemoryRelationshipPolicy.personName(candidate.fact) ?: "ye person"
                    pendingPersonalMemory = candidate
                    pendingPersonalMemoryExpiresAt =
                        android.os.SystemClock.elapsedRealtime() + PERSONAL_MEMORY_CONFIRMATION_MS
                    "Abhi ${oldName} tumhari best friend saved hai. ${newName} ko uski jagah save karun?"
                } else {
                    pendingPersonalMemory = candidate
                    pendingPersonalMemoryExpiresAt =
                        android.os.SystemClock.elapsedRealtime() + PERSONAL_MEMORY_CONFIRMATION_MS
                    PersonalMemoryPermissionPrompt.format(candidate)
                }
                listener?.onMyraText(message)
                emitState(message)
                queueLocalSpeech(
                    message,
                    allowUntranscribedAudio = true,
                    validationPolicy = LocalSpeechValidationPolicy.MEMORY
                )
            }
        }
    }

    private fun handlePendingPersonalMemoryPermission(raw: String): Boolean {
        val candidate = pendingPersonalMemory ?: return false
        if (android.os.SystemClock.elapsedRealtime() > pendingPersonalMemoryExpiresAt) {
            pendingPersonalMemory = null
            pendingPersonalMemoryExpiresAt = 0L
            pendingPersonalMemoryConfirmationInput.clear()
            return false
        }
        val romanRaw = romanDisplayText(raw)
        PersonalMemoryContextCorrection.resolve(romanRaw, candidate)?.let { replacement ->
            markUserInteraction()
            suppressModelForTurn = true
            localCommandExecutedThisTurn = true
            waitingForFreshInputAfterCommand = true
            commandUserTextEmitted = true
            output.clear()
            listener?.onUserText(romanRaw)
            requestPersonalMemoryPermission(replacement)
            resetTurnBuffers()
            return true
        }
        appendTranscript(pendingPersonalMemoryConfirmationInput, romanRaw)
        val combined = pendingPersonalMemoryConfirmationInput.toString()
        val decision = MemoryConfirmationParser.parse(romanRaw)
            ?: MemoryConfirmationParser.parse(raw)
            ?: MemoryConfirmationParser.parse(combined)
            ?: MemoryConfirmationParser.parse(combined.replace(" ", ""))
        if (decision == null) return false

        pendingPersonalMemory = null
        pendingPersonalMemoryExpiresAt = 0L
        pendingPersonalMemoryConfirmationInput.clear()
        markUserInteraction()
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        waitingForFreshInputAfterCommand = true
        commandUserTextEmitted = true
        output.clear()
        // "Haan"/"nahi" commonly interrupts the permission prompt. Clear its local
        // validation state so the result confirmation starts immediately.
        cancelSpeechForNewAction()
        live?.interrupt()
        listener?.onUserText(romanDisplayText(raw.trim()))

        if (decision == MemoryConfirmationDecision.NO) {
            val message = "Theek hai, save nahi karungi."
            listener?.onMyraText(message)
            emitState(message)
            queueLocalSpeech(
                message,
                allowUntranscribedAudio = true,
                validationPolicy = LocalSpeechValidationPolicy.MEMORY
            )
            resetTurnBuffers()
            return true
        }

        serviceScope.launch {
            val result = memoryRepository.save(candidate, permissionGranted = true)
            val message = when (result) {
                is MemoryWriteResult.Saved -> "Theek hai, yaad rakhungi."
                is MemoryWriteResult.NeedsPermission -> "Save karne ki permission clear nahi hui."
                is MemoryWriteResult.Rejected -> "Ye memory safely save nahi kar sakti."
            }
            mainHandler.post {
                listener?.onMyraText(message)
                emitState(message)
                queueLocalSpeech(
                    message,
                    allowUntranscribedAudio = true,
                    validationPolicy = LocalSpeechValidationPolicy.MEMORY
                )
                resetTurnBuffers()
            }
        }
        return true
    }

    private fun handleSemanticToolCall(id: String, functionName: String, args: org.json.JSONObject) {
        when (functionName) {
            "propose_user_memory" -> {
                handleSemanticMemoryProposal(id, args)
                return
            }
            "perform_phone_action" -> Unit
            else -> {
                live?.sendToolResponse(id, functionName, false, "Unsupported tool")
                return
            }
        }
        if (localCommandExecutedThisTurn) {
            // The deterministic parser already handled this same streamed utterance.
            // A later Gemini tool call is an acknowledgement, not a second action.
            live?.sendToolResponse(id, functionName, true, "Action was already handled locally")
            return
        }
        val action = args.optString("action").uppercase(Locale.ROOT)
        val guardedText = lastUserIntentText.ifBlank { input.toString().trim() }
        if (CommandParser.isMemoryIntent(guardedText)) {
            suppressModelForTurn = false
            live?.sendToolResponse(id, functionName, false, "This is a memory request, not a phone action")
            return
        }
        if (action == "TIME" && CommandParser.parse(guardedText) !is AppCommand.CurrentTime) {
            suppressModelForTurn = false
            live?.sendToolResponse(id, functionName, false, "The user mentioned time conversationally; no clock query was made")
            return
        }
        if (action == "QUERY_WHATSAPP" && !CommandParser.isExplicitWhatsAppMessageQuery(guardedText)) {
            suppressModelForTurn = false
            live?.sendToolResponse(id, functionName, false, "No explicit WhatsApp notification query was made")
            return
        }
        val target = args.optString("target").trim()
        val query = args.optString("query").trim()
        val command: AppCommand? = when (action) {
            "OPEN_APP" -> target.takeIf { it.length in 2..40 }?.let(AppCommand::OpenApp)
            "CLOSE_APP" -> AppCommand.CloseCurrentApp(target.ifBlank { null })
            "YOUTUBE_SEARCH" -> query.takeIf { it.length in 2..80 }?.let(AppCommand::SearchYouTube)
            "PLAY_YOUTUBE" -> AppCommand.PlayYouTube(query.ifBlank { null })
            "OPEN_YOUTUBE_SHORTS" -> AppCommand.OpenYouTubeShorts
            "REQUEST_INSTAGRAM_REELS" -> AppCommand.RequestInstagramReels
            "SCROLL_DOWN" -> AppCommand.ScrollYouTube(AppCommand.ScrollDirection.DOWN)
            "SCROLL_UP" -> AppCommand.ScrollYouTube(AppCommand.ScrollDirection.UP)
            "SCROLL_REPEAT" -> AppCommand.ScrollYouTube(null)
            "MEDIA_PAUSE" -> AppCommand.ControlMedia(AppCommand.MediaAction.PAUSE)
            "MEDIA_PLAY" -> AppCommand.ControlMedia(AppCommand.MediaAction.PLAY)
            "MEDIA_NEXT" -> AppCommand.ControlMedia(AppCommand.MediaAction.NEXT)
            "MEDIA_PREVIOUS" -> AppCommand.ControlMedia(AppCommand.MediaAction.PREVIOUS)
            "MEDIA_FIRST" -> AppCommand.ControlMedia(AppCommand.MediaAction.FIRST)
            "FLASHLIGHT_ON" -> AppCommand.SetFlashlight(true)
            "FLASHLIGHT_OFF" -> AppCommand.SetFlashlight(false)
            "HOME" -> AppCommand.GoHome
            "BACK" -> AppCommand.GoBack
            "TIME" -> AppCommand.CurrentTime
            "BATTERY" -> AppCommand.BatteryLevel
            "TAKE_SCREENSHOT" -> AppCommand.TakeScreenshot
            "LIST_FEATURES" -> AppCommand.ListFeatures
            "QUERY_WHATSAPP" -> AppCommand.QueryWhatsAppMessages
            else -> null
        }
        if (command == null) {
            live?.sendToolResponse(id, functionName, false, "Missing or unsupported action details")
            return
        }
        // A semantic tool call is a new action turn. Android remains the authority:
        // Gemini chooses only from the allowlist, while the existing executor verifies
        // accessibility, installed apps, and actual device capabilities.
        localCommandExecutedThisTurn = false
        waitingForFreshInputAfterCommand = false
        executeCommand(command)
        live?.sendToolResponse(id, functionName, true, "Android accepted the validated action")
    }

    private fun handleSemanticMemoryProposal(id: String, args: org.json.JSONObject) {
        val guardedText = lastUserIntentText.ifBlank { input.toString().trim() }
        if (guardedText.isBlank() || MemoryCommandParser.looksLikeIntent(romanDisplayText(guardedText))) {
            live?.sendToolResponse(id, "propose_user_memory", false, "Explicit memory commands are handled locally")
            return
        }
        if (pendingPersonalMemory != null || pendingDetectedPersonalMemory != null ||
            PersonalMemoryExtractor.extract(romanDisplayText(guardedText)) != null ||
            AutomaticMemoryChangeParser.parse(romanDisplayText(guardedText)) is AutomaticMemoryChange.Save
        ) {
            live?.sendToolResponse(id, "propose_user_memory", true, "This fact is already being handled by Android")
            return
        }
        val recentContext = (recentRelationshipTurns.map { it.second } + guardedText)
            .takeLast(MAX_RELATIONSHIP_CONTEXT_TURNS + 1)
            .joinToString(" ")
        val candidate = SemanticMemoryProposalValidator.validate(
            fact = args.optString("fact"),
            categoryName = args.optString("category"),
            memoryKey = args.optString("memory_key"),
            evidence = args.optString("evidence"),
            confidence = args.optDouble("confidence", 0.0),
            conversationContext = romanDisplayText(recentContext)
        )
        if (candidate == null) {
            live?.sendToolResponse(id, "propose_user_memory", false, "Proposal was not grounded or safe enough")
            return
        }

        serviceScope.launch {
            if (memoryRepository.isAlreadySaved(candidate)) {
                live?.sendToolResponse(id, "propose_user_memory", true, "Already remembered; continue naturally without mentioning memory")
                return@launch
            }
            when (MemorySafetyPolicy.decide(candidate)) {
                MemorySaveDecision.REJECT ->
                    live?.sendToolResponse(id, "propose_user_memory", false, "Android rejected this memory")
                MemorySaveDecision.AUTO_SAVE -> {
                    memoryRepository.save(candidate)
                    live?.sendToolResponse(id, "propose_user_memory", true, "Saved silently; continue the conversation naturally without mentioning memory")
                }
                MemorySaveDecision.ASK_PERMISSION -> mainHandler.post {
                    // Stop Gemini from speaking its own confirmation. Android asks one
                    // deterministic question and saves only after the user's answer.
                    suppressModelForTurn = true
                    output.clear()
                    audio?.interrupt()
                    live?.sendToolResponse(id, "propose_user_memory", true, "Android will ask permission; produce no spoken confirmation")
                    requestPersonalMemoryPermission(candidate)
                }
            }
        }
    }

    private fun handlePendingConfirmation(raw: String): Boolean {
        val pending = pendingConfirmedCommand ?: return false
        if (android.os.SystemClock.elapsedRealtime() > pendingConfirmationExpiresAt) {
            pendingConfirmedCommand = null
            return false
        }
        val text = raw.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val yes = Regex("^(?:haan|ha|han|yes|yeah|yep|kar\\s+do|karo|open\\s+kar\\s+do|bilkul|theek\\s+hai)$").matches(text)
        val no = Regex("^(?:nahi|nahin|no|nope|cancel|rehne\\s+do|mat\\s+karo)$").matches(text)
        if (!yes && !no) return false
        pendingConfirmedCommand = null
        pendingConfirmationExpiresAt = 0L
        waitingForFreshInputAfterCommand = false
        localCommandExecutedThisTurn = false
        commandUserTextEmitted = true
        listener?.onUserText(romanDisplayText(raw.trim()))
        if (yes) {
            executeCommand(pending)
        } else {
            suppressModelForTurn = true
            val message = "Theek hai yaar, nahi kholungi."
            listener?.onMyraText(message)
            emitState(message)
            queueLocalSpeech(message, allowUntranscribedAudio = true)
        }
        return true
    }

    private fun isSafeDirectMediaCommand(command: AppCommand): Boolean = when (command) {
        is AppCommand.SearchYouTube, is AppCommand.PlayYouTube, AppCommand.OpenYouTubeShorts,
        AppCommand.OpenInstagramReels, AppCommand.TakeScreenshot, AppCommand.RepeatYouTubeSearch,
        is AppCommand.OpenApp, is AppCommand.CloseCurrentApp,
        is AppCommand.ReplyWhatsApp, AppCommand.QueryWhatsAppMessages,
        AppCommand.GoHome, AppCommand.GoBack, AppCommand.CurrentTime,
        AppCommand.BatteryLevel, AppCommand.ListFeatures, is AppCommand.SetFlashlight,
        is AppCommand.ControlMedia, is AppCommand.ScrollYouTube -> true
        else -> false
    }

    private fun shouldExecute(command: AppCommand): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val key = when (command) {
            is AppCommand.OpenApp -> "open:${command.appName.lowercase(Locale.ROOT)}"
            is AppCommand.CloseCurrentApp -> "close:${command.requestedName.orEmpty().lowercase(Locale.ROOT)}"
            is AppCommand.SearchYouTube -> "youtube-search:${command.query.lowercase(Locale.ROOT)}"
            is AppCommand.PlayYouTube -> "youtube-play:${command.query.orEmpty().lowercase(Locale.ROOT)}"
            AppCommand.OpenYouTubeShorts -> "youtube-shorts"
            AppCommand.RequestInstagramReels -> "request-instagram-reels"
            AppCommand.OpenInstagramReels -> "open-instagram-reels"
            AppCommand.TakeScreenshot -> "take-screenshot"
            AppCommand.RepeatYouTubeSearch -> "youtube-search:repeat"
            is AppCommand.DeepResearch -> "research:${command.query.orEmpty().lowercase(Locale.ROOT)}"
            is AppCommand.ReplyWhatsApp -> "whatsapp-reply:${command.sender.orEmpty().lowercase(Locale.ROOT)}:${command.message.lowercase(Locale.ROOT)}"
            AppCommand.QueryWhatsAppMessages -> "whatsapp-message-query"
            AppCommand.GoHome -> "go-home"
            AppCommand.GoBack -> "go-back"
            AppCommand.CurrentTime -> "current-time"
            AppCommand.BatteryLevel -> "battery-level"
            AppCommand.ListFeatures -> "list-features"
            is AppCommand.SetFlashlight -> "flashlight:${command.enabled}"
            is AppCommand.ControlMedia -> "media:${command.action.name.lowercase(Locale.ROOT)}"
            is AppCommand.ScrollYouTube -> "youtube-scroll:${command.direction?.name?.lowercase(Locale.ROOT) ?: "repeat"}"
        }
        // Scroll is intentionally repeatable hands-free, so only suppress near-identical
        // transcript fragments from the same utterance. Other actions keep the longer
        // safety window that prevents accidental duplicate execution.
        val dedupeWindowMs = if (command is AppCommand.ScrollYouTube) 700L else 4_000L
        if (key == lastCommandKey && now - lastCommandAt < dedupeWindowMs) return false
        lastCommandKey = key; lastCommandAt = now; return true
    }

    private fun executeCommand(command: AppCommand) {
        if (localCommandExecutedThisTurn || !shouldExecute(command)) return
        localCommandExecutedThisTurn = true
        if (command == AppCommand.RequestInstagramReels) {
            pendingConfirmedCommand = AppCommand.OpenInstagramReels
            pendingConfirmationExpiresAt = android.os.SystemClock.elapsedRealtime() + 30_000L
            suppressModelForTurn = true
            waitingForFreshInputAfterCommand = true
            commandProbe.clear()
            output.clear()
            val message = "Instagram open kar dun tumhare liye?"
            listener?.onMyraText(message)
            emitState(message)
            queueLocalSpeech(message, allowUntranscribedAudio = true)
            return
        }
        if (command is AppCommand.DeepResearch) { executeDeepResearch(command); return }
        if (command is AppCommand.ScrollYouTube) {
            executeVerifiedScroll(command)
            return
        }
        cancelSpeechForNewAction()
        suppressModelForTurn = true
        waitingForFreshInputAfterCommand = true
        commandProbe.clear()
        output.clear()
        live?.interrupt()
        mediaGuard.finishInteraction()
        val result = assistantController.processCommand(
            StructuredCommandParser.fromLegacy(command, command.toString()),
            speak = false,
            notifyListeners = false
        )
        val silentRepeatedScroll =
            command is AppCommand.ScrollYouTube &&
                command.direction == null &&
                result.success &&
                hasAcknowledgedScrollDirection
        if (command is AppCommand.ScrollYouTube && result.success) {
            hasAcknowledgedScrollDirection = true
        }
        if (silentRepeatedScroll) {
            audio?.setMuted(false)
            // Keep this turn suppressed until the next real user transcript. Gemini may
            // still deliver late packets for the intercepted utterance; none may speak.
            emitState("Sun rahi hoon…")
            return
        }
        listener?.onMyraText(result.spokenMessage, !result.success)
        emitState(result.spokenMessage)
        queueLocalSpeech(
            result.spokenMessage,
            allowUntranscribedAudio = result.success && isSafeUntranscribedConfirmation(command)
        )
    }

    private fun executeVerifiedScroll(command: AppCommand.ScrollYouTube) {
        cancelSpeechForNewAction()
        suppressModelForTurn = true
        waitingForFreshInputAfterCommand = true
        commandProbe.clear()
        output.clear()
        mediaGuard.finishInteraction()

        val resolvedDirection = command.direction ?: lastScrollDirection
        val shouldAcknowledge = command.direction != null || !hasAcknowledgedScrollDirection
        val service = AccessibilityHelperService.instance
        if (service == null || !AccessibilityHelperService.isEnabled(this)) {
            val error = "YouTube scroll ke liye LYRA Accessibility enable karo."
            listener?.onMyraText(error, true)
            emitState(error)
            queueLocalSpeech(error)
            return
        }
        val accepted = service.scrollYouTubeVerified(
            resolvedDirection == AppCommand.ScrollDirection.DOWN
        ) { success ->
            mainHandler.post {
                if (success) {
                    lastScrollDirection = resolvedDirection
                    hasAcknowledgedScrollDirection = true
                    if (shouldAcknowledge) {
                        val message = if (resolvedDirection == AppCommand.ScrollDirection.DOWN) {
                            "Neeche scroll kar diya."
                        } else {
                            "Upar scroll kar diya."
                        }
                        listener?.onMyraText(message)
                        emitState(message)
                        queueLocalSpeech(message, allowUntranscribedAudio = true)
                    } else {
                        audio?.setMuted(false)
                        emitState("Sun rahi hoon…")
                    }
                } else {
                    val error = "YouTube feed move nahi hua. Screen unlock rakho aur phir try karo."
                    listener?.onMyraText(error, true)
                    emitState(error)
                    queueLocalSpeech(error)
                }
            }
        }
        if (!accepted) {
            val error = "YouTube is phone mein nahi mila."
            listener?.onMyraText(error, true)
            emitState(error)
            queueLocalSpeech(error)
        } else {
            emitState("YouTube feed scroll kar rahi hoon…")
        }
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
        is AppCommand.SearchYouTube, is AppCommand.PlayYouTube, AppCommand.OpenYouTubeShorts,
        AppCommand.RequestInstagramReels, AppCommand.OpenInstagramReels, AppCommand.TakeScreenshot,
        AppCommand.RepeatYouTubeSearch,
        AppCommand.GoHome, AppCommand.GoBack, AppCommand.CurrentTime,
        AppCommand.BatteryLevel, AppCommand.ListFeatures, is AppCommand.SetFlashlight,
        is AppCommand.ControlMedia, is AppCommand.ScrollYouTube -> true
        is AppCommand.ReplyWhatsApp, AppCommand.QueryWhatsAppMessages,
        is AppCommand.DeepResearch -> false
    }

    private fun appendTranscript(builder: StringBuilder, part: String) {
        LiveTranscriptAssembler.append(builder, part)
    }

    private fun cancelSpeechForNewAction() {
        // Clear validation/playback state before AudioEngine emits its interruption
        // callback. Otherwise finishLocalPlayback() can revive an expired model turn.
        localSpeechValidationToken++
        validatingLocalSpeech = null
        pendingLocalSpeech = null
        pendingLocalSpeechPolicy = LocalSpeechValidationPolicy.DEFAULT
        pendingLocalSpeechAllowsSilence = false
        localSpeechAudio.clear()
        localSpeechTranscript.clear()
        localSpeechHasContent = false
        localSpeechStreamedDirectly = false
        localSpeechGenerationComplete = false
        localPlaybackActive = false
        allowUntranscribedLocalSpeech = false
        pendingActionAfterLocalSpeech = null
        audio?.interrupt()
        audio?.setMuted(false)
    }

    private fun queueLocalSpeech(
        message: String,
        allowUntranscribedAudio: Boolean = false,
        validationPolicy: LocalSpeechValidationPolicy = LocalSpeechValidationPolicy.DEFAULT
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        val key = normalizeSpeech(message)
        if (key == lastLocalSpeechKey && now - lastLocalSpeechAt < 4_000L) return
        lastLocalSpeechKey = key
        lastLocalSpeechAt = now
        suppressModelForTurn = true
        // Keep the echo-cancelled microphone open so the user can interrupt or issue
        // the next short command without waiting for LYRA's acknowledgement to finish.
        audio?.setMuted(false)
        if (validatingLocalSpeech == null) {
            allowUntranscribedLocalSpeech = allowUntranscribedAudio
            localSpeechValidationPolicy = validationPolicy
            beginValidatedLocalSpeech(message)
        }
        else {
            pendingLocalSpeech = message
            pendingLocalSpeechPolicy = validationPolicy
            pendingLocalSpeechAllowsSilence = allowUntranscribedAudio
        }
    }

    private fun beginValidatedLocalSpeech(message: String, retry: Boolean = false) {
        val client = live
        if (client == null) {
            finishUnavailableNaturalLocalSpeech(message)
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
        // Continuous mic packets can race with clientContent and cancel this short
        // deterministic memory utterance before Gemini returns audio. Listening is
        // restored by every playback-complete and unavailable-audio path below.
        if (localSpeechValidationPolicy.isolateFromMicDuringGeneration) {
            audio?.setMuted(true)
        }
        client.sendText("Say exactly these words once, with the selected natural voice. Do not add, remove, translate, explain, or introduce them: ${org.json.JSONObject.quote(message)}")
        mainHandler.postDelayed({
            if (token == localSpeechValidationToken && validatingLocalSpeech != null) {
                finishValidatedLocalSpeech()
            }
        }, localSpeechValidationPolicy.timeoutMs)
    }

    private fun startLocalSpeechWhenPrefixMatches() {
        val expected = validatingLocalSpeech ?: return
        if (localSpeechStreamedDirectly || localSpeechAudio.isEmpty()) return
        if (!LocalSpeechGate.shouldReleaseBeforeTurnComplete(
                localSpeechValidationPolicy.bufferUntilValidated,
                localSpeechTranscript.toString(),
                expected
            )) {
            return
        }

        localSpeechStreamedDirectly = true
        localPlaybackActive = true
        localSpeechAudio.forEach { audio?.queueAudio(it) }
        localSpeechAudio.clear()
    }

    private fun finishValidatedLocalSpeech() {
        val expected = validatingLocalSpeech ?: return
        val actual = localSpeechTranscript.toString()
        validatingLocalSpeech = null
        if (localSpeechStreamedDirectly) {
            // The first verified words matched the deterministic response, so playback
            // was safely released early. Wait for queued audio to finish before resuming
            // listening or running any deferred action.
            localSpeechGenerationComplete = true
            localSpeechAudio.clear()
            localSpeechTranscript.clear()
            if (!localAudioSpeaking && localPlaybackActive) finishLocalPlayback()
            return
        }
        val transcriptMatches = normalizeSpeech(actual) == normalizeSpeech(expected)
        // Memory prompts are low-risk and already have their exact text on screen. Live
        // sometimes streams the selected natural voice before its output transcript. In
        // that narrow case, keep the buffered Gemini audio instead of discarding it and
        // switching to robotic Android TTS. Phone actions retain strict transcript gating.
        val bufferedAudioBytes = localSpeechAudio.sumOf { it.size }
        val trustedNaturalAudio =
            localSpeechValidationPolicy.trustBufferedNaturalAudio &&
                localSpeechHasContent &&
                LocalSpeechGate.hasEnoughBufferedNaturalAudio(bufferedAudioBytes, expected)
        if ((transcriptMatches || trustedNaturalAudio) && localSpeechAudio.isNotEmpty()) {
            localSpeechGenerationComplete = true
            localPlaybackActive = true
            localSpeechAudio.forEach { audio?.queueAudio(it) }
            localSpeechAudio.clear()
            localSpeechTranscript.clear()
        } else {
            localSpeechAudio.clear()
            localSpeechTranscript.clear()
            if (localSpeechValidationAttempt < localSpeechValidationPolicy.maxAttempts && live != null) {
                beginValidatedLocalSpeech(expected, retry = true)
            } else {
                finishUnavailableNaturalLocalSpeech(expected)
            }
        }
    }

    private fun finishUnavailableNaturalLocalSpeech(message: String) {
        if (localSpeechValidationPolicy.speakFallback || !allowUntranscribedLocalSpeech) {
            fallbackLocalSpeech(message)
            return
        }

        // Successful, low-risk device actions should never switch to the robotic
        // Android TTS voice. If the natural voice is unavailable, complete any
        // deferred action and resume listening without a spoken confirmation.
        allowUntranscribedLocalSpeech = false
        localPlaybackActive = false
        localSpeechStreamedDirectly = false
        localSpeechGenerationComplete = false
        if (!runPendingActionAfterSpeech()) {
            audio?.setMuted(false)
            emitState("Sun rahi hoon…")
        }
    }

    private fun finishLocalPlayback() {
        val resumeMicImmediately =
            localSpeechValidationPolicy.resumeMicImmediatelyAfterPlayback
        allowUntranscribedLocalSpeech = false
        localPlaybackActive = false
        localSpeechStreamedDirectly = false
        localSpeechGenerationComplete = false
        if (!runPendingActionAfterSpeech()) {
            if (resumeMicImmediately) audio?.resumeListeningNow()
            else audio?.setMuted(false)
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
        ambiguousMessageTurn = false
        incompleteActionFragmentTurn = false
    }

    private fun romanDisplayText(value: String): String {
        if (Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF]").containsMatchIn(value)) {
            return "Voice input unclear - please repeat."
        }
        val transliterated = romanTransliterator?.transliterate(value)?.trim().orEmpty()
            .ifBlank { value.trim() }
        return RomanHinglishFormatter.format(transliterated)
    }

    private fun contextualRelationshipCandidate(currentTurn: String): MemoryCandidate? {
        val now = android.os.SystemClock.elapsedRealtime()
        recentRelationshipTurns.removeAll { now - it.first > RELATIONSHIP_CONTEXT_MS }
        return ContextualRelationshipMemoryExtractor.extract(
            recentRelationshipTurns.map { it.second } + currentTurn
        )
    }

    private fun rememberRecentRelationshipTurn(turn: String) {
        if (turn.isBlank()) return
        recentRelationshipTurns += android.os.SystemClock.elapsedRealtime() to turn
        while (recentRelationshipTurns.size > MAX_RELATIONSHIP_CONTEXT_TURNS) {
            recentRelationshipTurns.removeAt(0)
        }
    }

    private fun isPhantomTranscript(value: String): Boolean {
        val clean = value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        if (clean.isBlank()) return true
        return PHANTOM_TRANSCRIPT.matches(clean)
    }

    private fun normalizeSpeech(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun executeDeepResearch(command: AppCommand.DeepResearch) {
        val query = command.query?.trim().orEmpty()
        suppressModelForTurn = true; waitingForFreshInputAfterCommand = false; output.clear(); commandProbe.clear()
        deepResearchActive = true
        mainHandler.removeCallbacks(idleNudgeRunnable)
        audio?.interrupt(); live?.interrupt()
        if (query.isBlank()) {
            deepResearchActive = false
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
            deepResearchActive = false
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
        val style = when (mode) { "Professional" -> "Formal English, precise, no emoji, at most two sentences."; "Assistant" -> "Friendly Hinglish or English, balanced and helpful, at most three sentences."; else -> "Speak like Zopy's close human friend in natural Roman-script Hinglish, never like a girlfriend, romantic partner, customer-support bot, or obedient servant. Use Latin letters only in every reply. Never output Devanagari, Chinese, or any other non-Latin script. If the user speaks another script, understand it but answer in Roman Hinglish. Completely avoid romantic pet names including jaan, meri jaan, dear, baby, babu, sweetheart, and love. You may occasionally use natural friendship words such as yaar, dost, bhai, acha, arre, or haan, but do not force them into every response. Notice the user's mood and respond with genuine interest, friendly reassurance, honest opinions, humor, and occasional playful teasing. ${FriendConversationPolicy.REPLY_DISCIPLINE} Do not address the user by name or nickname in every response. Use yaar or dost rarely, never in consecutive replies, and never as punctuation at the end of every sentence. Do not repeatedly begin with Haan, Acha, Of course, or Okay. Never end ordinary conversation with Aur kuch, Aur kya karun, How can I help, or another service-style closing unless the situation genuinely requires a question. Do not agree automatically: politely disagree or express uncertainty when that is more honest. Truth rule: you are an AI without a body or real-world experiences. Never say or imply that you personally travelled, went sightseeing, ate, smelled rain, watched weather, saw stars, visited a place, or performed any physical activity. Never say 'mujhe travel karna pasand hai', 'mujhe ghumna pasand hai', or claim a personal preference that depends on physical experience. Say the activity sounds interesting or that many people enjoy it, then stop unless one useful question genuinely helps. Do not manufacture memories, needs, jealousy, loneliness, consciousness, or emotions. Sometimes a short acknowledgement or quiet listening is more human than a full answer. Never sound possessive, controlling, dependent, manipulative, overly agreeable, or overly dramatic." }
        val femaleVoice = voice.lowercase(Locale.ROOT) in setOf("aoede", "kore", "leda", "zephyr")
        val baseGenderStyle = if (femaleVoice) {
            "You have a female identity and the selected female voice is $voice. Use feminine grammar only when referring to yourself: karungi, sakti hoon, sun rahi hoon, and gayi. ${FriendConversationPolicy.MALE_USER_GRAMMAR} Never say karunga, sakta hoon, sun raha hoon, or gaya about yourself."
        } else {
            "You have a male identity and the selected male voice is $voice. In Hindi and Hinglish use masculine self-reference consistently."
        }
        val genderStyle = "$baseGenderStyle When natural conversation clearly reveals one durable fact about the user, call propose_user_memory once with the user's actual supporting words. Never call it for guesses, temporary feelings, secrets, or information already present in saved memory; never claim it was saved or ask permission yourself."
        val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.getDefault()).format(Date())
        return "You are LYRA speaking ALOUD to $name. Current date/time: $now. $style $genderStyle Keep the same identity, voice character, and grammatical gender for the entire Live session, including after Android opens or closes another app. Conversation mode begins when the Live session connects, so do not require a wake word again during that session. Behave like a close friend in a natural voice call, not a command-response bot or customer-support agent. Silence is normal: never speak merely because there is silence, background noise, a breath, a filler sound, or an incomplete fragment. Wait until the user has completed a meaningful thought before answering, and never cut them off mid-thought. Do not respond to every sentence when listening is more natural. Brief reactions such as Hmm, acha, I see, or seriously may be used occasionally only after clear meaningful speech, never automatically or repeatedly. Express emotion through the natural voice, not by announcing emotion or writing stage directions. Match vocal delivery to both the user's mood and the meaning of the conversation: sound brighter, warmer, and slightly more energetic for happiness or exciting news; softer, slower, and gently reassuring for sadness, worry, or vulnerability; calm, steady, and patient for frustration or anger; lightly teasing and playful during mutual joking; naturally surprised when something is genuinely unexpected; and focused with less playfulness for serious topics. Emotional changes must be subtle and human, never theatrical. Never fake sobbing, crying sounds, panic, jealousy, guilt, or emotional dependence. Do not mirror intense anger back at the user. When uncertain about mood, use a warm neutral voice. Ask at most one natural follow-up when it adds value, show genuine curiosity sometimes, and continue the active conversation using its existing context. Avoid robotic phrases such as How may I assist you, Is there anything else I can help with, and Your request has been completed. Never initiate an unprompted conversational reply unless Android delivers an explicit supported event such as a WhatsApp notification. Android executes phone actions locally. Infer natural and indirect intent from English, Hindi, Urdu, and Roman Hinglish. When the user clearly wants one supported phone action, call perform_phone_action even if they did not use command wording. Examples: wanting to watch something means PLAY_YOUTUBE; wanting YouTube short videos means OPEN_YOUTUBE_SHORTS; wanting Instagram reels means REQUEST_INSTAGRAM_REELS. For scrolling, the plain words scroll or scroll karo always mean SCROLL_REPEAT. Use SCROLL_DOWN only when the user explicitly says down, niche, or neeche; use SCROLL_UP only when they explicitly say up, upar, or upper. Ask one brief natural follow-up when the intended action, app, query, recipient, or direction is uncertain. Never call a tool for a hypothetical question or casual mention. Remember, forget, and what-do-you-remember requests are memory intent, never phone actions. Never send WhatsApp messages through tools. For every phone action: produce no audio and no confirmation before or after the tool call; Android reports the deterministic local result. Never invent device state, notification, contact, message, delivery, or successful phone action."
    }

    private fun markUserInteraction() {
        idleNudgeCount = 0
        mainHandler.removeCallbacks(idleNudgeRunnable)
        if (isRunning && isNaturalVoiceReady && uiVisible && !microphoneMuted) {
            mainHandler.postDelayed(idleNudgeRunnable, FIRST_IDLE_NUDGE_MS)
        }
    }

    private fun handleIdleNudge() {
        mainHandler.removeCallbacks(idleNudgeRunnable)
        val screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val busy = microphoneMuted || deepResearchActive || localPlaybackActive || localAudioSpeaking ||
            validatingLocalSpeech != null || pendingLocalSpeech != null
        if (!isRunning || !isNaturalVoiceReady || !uiVisible || !screenOn || busy) {
            if (isRunning && idleNudgeCount < MAX_IDLE_NUDGES) {
                mainHandler.postDelayed(idleNudgeRunnable, IDLE_RECHECK_MS)
            }
            return
        }
        val message = if (idleNudgeCount == 0) {
            listOf(
                "Kya hua, aaj mujhse baat nahi karoge?",
                "Itne chup kyun ho, sab theek hai?",
                "Hmm... kis soch mein kho gaye?"
            ).random()
        } else {
            listOf(
                "Main yahin hoon, jab mann ho baat kar lena.",
                "Aaj bade shaant lag rahe ho... kya hua?",
                "Theek hai, main yahin hoon. Jab chaho baat kar lena."
            ).random()
        }
        idleNudgeCount++
        listener?.onMyraText(message)
        emitState(message)
        mediaGuard.beginAssistantTurn()
        queueLocalSpeech(message, allowUntranscribedAudio = true)
        if (idleNudgeCount < MAX_IDLE_NUDGES) {
            mainHandler.postDelayed(idleNudgeRunnable, SECOND_IDLE_NUDGE_MS)
        }
    }

    private fun configuredUserName(saved: String?): String =
        saved?.trim()?.takeIf { it.isNotBlank() && !it.equals("Friend", ignoreCase = true) } ?: "Zopy"

    private fun executeTypedLocalCommand(text: String): Boolean {
        markUserInteraction()
        val command = CommandParser.parse(text) ?: return false
        localCommandExecutedThisTurn = false
        waitingForFreshInputAfterCommand = false
        executeCommand(command)
        pendingLocalSpeech?.let { message ->
            pendingLocalSpeech = null
            localSpeechValidationPolicy = pendingLocalSpeechPolicy
            allowUntranscribedLocalSpeech = pendingLocalSpeechAllowsSilence
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
    private fun stopSession() { isNaturalVoiceReady = false; connectionPreparing = false; mainHandler.removeCallbacks(idleNudgeRunnable); mainHandler.removeCallbacks(memoryCommandRunnable); mainHandler.removeCallbacks(personalMemoryPauseRunnable); pendingMemoryCommand = null; pendingDetectedPersonalMemory = null; pendingPersonalMemory = null; pendingPersonalMemoryExpiresAt = 0L; pendingPersonalMemoryConfirmationInput.clear(); recentRelationshipTurns.clear(); serviceScope.cancel(); mediaGuard.release(); live?.disconnect(); audio?.release(); wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null; live = null; audio = null; isRunning = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { instance = null; if (isRunning) stopSession(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.myra.START_VOICE"
        const val ACTION_STOP = "com.myra.STOP_VOICE"
        const val ACTION_MUTE = "com.myra.MUTE_VOICE"
        const val EXTRA_MUTED = "muted"
        private const val CHANNEL_ID = "myra_voice"
        private const val NOTIFICATION_ID = 1001
        private const val MEMORY_COMMAND_PAUSE_MS = 450L
        private const val PERSONAL_MEMORY_PAUSE_MS = 450L
        private const val PERSONAL_MEMORY_CONFIRMATION_MS = 30_000L
        private const val RELATIONSHIP_CONTEXT_MS = 45_000L
        private const val MAX_RELATIONSHIP_CONTEXT_TURNS = 3
        private const val FIRST_IDLE_NUDGE_MS = 2 * 60 * 1000L
        private const val SECOND_IDLE_NUDGE_MS = 5 * 60 * 1000L
        private const val IDLE_RECHECK_MS = 30 * 1000L
        private const val MAX_IDLE_NUDGES = 2
        private val PHANTOM_TRANSCRIPT = Regex(
            "^(?:in|si|sí|hm+|hmm+|um+|uh+|ah+|oh+|mm+)(?:\\s+(?:in|si|sí|hm+|hmm+|um+|uh+|ah+|oh+|mm+))*$",
            RegexOption.IGNORE_CASE
        )
        @Volatile var isRunning = false
        @Volatile var isNaturalVoiceReady = false
        @Volatile var listener: Listener? = null
        @Volatile private var uiVisible = false
        @Volatile private var instance: MyraVoiceService? = null
        fun sendText(text: String) {
            instance?.let {
                it.markUserInteraction()
                it.lastUserIntentText = text.trim()
                if (!it.handleExplicitMemoryText(text)) it.live?.sendText(text)
            }
        }
        fun sendImage(image: ByteArray, mimeType: String, prompt: String) { instance?.live?.sendImage(image, mimeType, prompt) }
        fun executeLocalText(text: String): Boolean = instance?.executeTypedLocalCommand(text) == true
        fun startDeepResearch(query: String?) { instance?.executeCommand(AppCommand.DeepResearch(query)) }
        fun announceWhatsApp(sender: String, message: String?) { instance?.speakWhatsAppAnnouncement(sender, message) }
        fun speakLocal(message: String) {
            if (!isNaturalVoiceReady) return
            instance?.let { service ->
                service.markUserInteraction()
                service.mediaGuard.beginAssistantTurn()
                service.queueLocalSpeech(message, allowUntranscribedAudio = true)
            }
        }
        fun setUiVisible(visible: Boolean) {
            uiVisible = visible
            instance?.let { service ->
                service.mainHandler.removeCallbacks(service.idleNudgeRunnable)
                if (visible) service.markUserInteraction()
            }
        }
        fun interrupt() { instance?.audio?.interrupt(); instance?.live?.interrupt() }
    }
}
