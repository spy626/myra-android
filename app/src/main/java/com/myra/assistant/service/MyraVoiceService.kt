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
import com.myra.assistant.diagnostics.VoicePipelineLogger
import androidx.core.app.NotificationCompat
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.ai.ApiKeyStore
import com.myra.assistant.ai.DeepResearchClient
import com.myra.assistant.ai.HandsFreeMediaGuard
import com.myra.assistant.ai.LyraPlaybackCapturePolicy
import com.myra.assistant.ai.LiveTranscriptAssembler
import com.myra.assistant.ai.MediaSpeechCoherencePolicy
import com.myra.assistant.brain.BrainDecision
import com.myra.assistant.brain.LyraBrainCoordinator
import com.myra.assistant.brain.ScreenTargetReference
import com.myra.assistant.brain.ScrollDirection as BrainScrollDirection
import com.myra.assistant.data.memory.AutomaticMemoryChange
import com.myra.assistant.data.memory.AutomaticMemoryChangeParser
import com.myra.assistant.data.memory.BestFriendNameCorrectionParser
import com.myra.assistant.data.memory.BestFriendNameCanonicalizer
import com.myra.assistant.data.memory.BestFriendNameCorrection
import com.myra.assistant.data.memory.ClarifiedPersonNameResolver
import com.myra.assistant.data.memory.ClarifiedNameResult
import com.myra.assistant.data.memory.ContextualRelationshipMemoryExtractor
import com.myra.assistant.data.memory.CorrectionSuccessPolicy
import com.myra.assistant.data.memory.LyraMemoryDatabase
import com.myra.assistant.data.memory.MemoryCommand
import com.myra.assistant.data.memory.MemoryCommandParser
import com.myra.assistant.data.memory.MemoryCommandReplyFormatter
import com.myra.assistant.data.memory.MemoryConfirmationDecision
import com.myra.assistant.data.memory.MemoryConfirmationParser
import com.myra.assistant.data.memory.MemoryCandidate
import com.myra.assistant.data.memory.PersonalMemoryExtractor
import com.myra.assistant.data.memory.PersonLinkedMemoryExtractor
import com.myra.assistant.data.memory.PersonalMemoryContextCorrection
import com.myra.assistant.data.memory.PersonalMemoryPermissionPrompt
import com.myra.assistant.data.memory.PersonalMemoryRecallFormatter
import com.myra.assistant.data.memory.MemoryRepository
import com.myra.assistant.data.memory.MemoryRelationshipPolicy
import com.myra.assistant.data.memory.SavedMemoryContextFormatter
import com.myra.assistant.data.memory.MemoryWriteResult
import com.myra.assistant.data.memory.MemorySafetyPolicy
import com.myra.assistant.data.memory.MemorySaveDecision
import com.myra.assistant.data.memory.MemoryCategory
import com.myra.assistant.data.memory.MemorySensitivity
import com.myra.assistant.data.memory.SemanticMemoryProposalValidator
import com.myra.assistant.data.memory.UnclearDeleteIntentGuard
import com.myra.assistant.data.memory.PendingDeleteClarification
import com.myra.assistant.model.AppCommand
import com.myra.assistant.phone.AppActionExecutor
import com.myra.assistant.MyApplication
import com.myra.assistant.commands.CommandParser as StructuredCommandParser
import com.myra.assistant.ui.main.MainActivity
import com.myra.assistant.screen.ScreenCaptureService
import com.myra.assistant.screen.ScreenPrivacyPolicy
import com.myra.assistant.screen.ScreenFramePrivacyFilter
import com.myra.assistant.screen.ScreenPrivacyResult
import com.myra.assistant.screen.ScreenQueryDispatchPolicy
import com.myra.assistant.screen.ScreenQueryTimingPolicy
import com.myra.assistant.screen.ScreenShareState
import com.myra.assistant.screen.ScreenVisionIntentParser
import com.myra.assistant.screen.InstantScreenQuery
import com.myra.assistant.screen.ScreenCacheUse
import com.myra.assistant.screen.ScreenContextStore
import com.myra.assistant.screen.HotScreenCachePolicy
import com.myra.assistant.screen.ScreenVisionPreferences
import com.myra.assistant.screen.FreshFrameResult
import com.myra.assistant.screen.ScreenResponseBinding
import com.myra.assistant.screen.ReadingCommand
import com.myra.assistant.screen.ReadingIntentParser
import com.myra.assistant.screen.ReadingState
import com.myra.assistant.screen.ReadingTracker
import com.myra.assistant.screen.ScreenCommandTurnGuard
import com.myra.assistant.screen.ScreenContentType
import com.myra.assistant.screen.ScreenActionIntent
import com.myra.assistant.screen.ScreenActionIntentRegistry
import com.myra.assistant.voice.LocalSpeechGate
import com.myra.assistant.voice.FinalTranscriptDisplayFormatter
import com.myra.assistant.voice.FinalTranscriptDuplicateGuard
import com.myra.assistant.voice.FinalSemanticUserUtterance
import com.myra.assistant.voice.FinalTranscriptPlausibilityGate
import com.myra.assistant.voice.PhantomTranscriptFilter
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

    const val BOSS_ASSISTANT_STYLE =
        "Use a subtle confident personal-assistant tone. You may occasionally say 'boss', 'on it', " +
            "'got it', or 'done' when it naturally fits a verified action, but never in every reply, " +
            "never more than once in a response, and never claim an action is done before Android verifies it."

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
    private val brain = LyraBrainCoordinator()
    private val readingTracker = ReadingTracker()
    private val screenCommandTurnGuard = ScreenCommandTurnGuard()
    private val screenActionRegistry = ScreenActionIntentRegistry()
    private var lastUserIntentText = ""
    private val recentRelationshipTurns = mutableListOf<Pair<Long, String>>()
    private var lastSavedBestFriendName: String? = null
    private var lastSavedBestFriendAt = 0L
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
    private var pendingDeleteClarificationUntil = 0L
    private var pendingBestFriendCorrectionOldName: String? = null
    private var pendingBestFriendCorrectionUntil = 0L
    private var pendingSpellingConfirmationName: String? = null
    private var turnSequence = 0L
    private var activeTurnId = 0L
    private var controlledGenerationId = 0L
    private val responseArbiter = TurnResponseArbiter()
    private val ordinaryModelAudioGate = OrdinaryModelAudioGate()
    private val transcriptSessionId = java.util.UUID.randomUUID().toString()
    private val transcriptPlausibilityGate = FinalTranscriptPlausibilityGate()
    private val finalUserMessageCommitter = FinalUserMessageCommitter()
    private val memoryCommandRunnable = Runnable {
        val command = pendingMemoryCommand
        pendingMemoryCommand = null
        if (command != null && !localCommandExecutedThisTurn) {
            val spoken = commandProbe.toString().trim()
            if (spoken.isNotBlank() && !commandUserTextEmitted) {
                commitFinalUserMessage(spoken, "MEMORY_COMMAND_RUNNABLE")
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
                commitFinalUserMessage(spoken, "PERSONAL_MEMORY_PAUSE")
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
    private var localSpeechTimeoutRunnable: Runnable? = null
    private var localSpeechTimeoutToken = 0L
    private val localSpeechTimeoutGate = ControlledSpeechTimeoutGate()
    private var localSpeechQueuedAt = 0L
    private var localSpeechRequestSentAt = 0L
    private var localSpeechFirstAudioReceivedAt = 0L
    private var localSpeechFirstAudioAcceptedAt = 0L
    private var localSpeechFirstPlaybackWriteAt = 0L
    private var localSpeechLastAudioReceivedAt = 0L
    private var instantScreenQueryId = ""
    private var instantScreenQueryStartedAt = 0L
    private var instantScreenCacheAgeMs = 0L
    private var modelAudioDroppedBeforeTurnCompleteCount = 0
    private var modelAudioDroppedBeforeTurnCompleteBytes = 0L
    private var acceptedModelGenerationForTurn = 0L
    private var speechActivityStartedAt = 0L
    private var speechActivityEndedAt = 0L
    private var speechTimingTurnId = 0L
    private var inputTurnStartedAt = 0L
    private var latestObservedModelGenerationId = 0L
    private var earlyModelAudioGenerationId = 0L
    private val earlyModelAudio = mutableListOf<ByteArray>()
    private var earlyModelAudioBytes = 0L
    private var localAudioSpeaking = false
    private var screenResponseActive = false
    private var screenResponseHasContent = false
    private var screenResponseStartedLogged = false
    private var screenResponseGenerationComplete = false
    private var screenResponseTextCommitted = false
    private var screenResponseUserTurnId = 0L
    private var screenResponseAfterGenerationId = 0L
    private var screenResponseGenerationId = 0L
    private var screenResponseBinding: ScreenResponseBinding? = null
    private var screenResponseSessionId = ""
    private var screenResponseQueryId = ""
    private var screenQuestionDetectedAt = 0L
    private var screenFreshFrameCapturedAt = 0L
    private var screenFrameSentAt = 0L
    private var screenResponseSpeechEndedAt = 0L
    private var screenQuerySpeechTurnConsistency = false
    private var armedScreenQuestion = ""
    private var armedScreenQuestionTurnId = 0L
    private var armedScreenQuestionDetectedAt = 0L
    private var earlyScreenQueryAwaitingFinalTranscript = false
    private var earlyScreenQueryDispatchedTurnId = 0L
    private var pendingCanonicalRename: kotlinx.coroutines.Job? = null
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
    private val screenVisionPreferences by lazy { ScreenVisionPreferences(this) }
    private val screenCaptureListener: (ScreenShareState, ByteArray?) -> Unit = { state, frame ->
        if (state == ScreenShareState.ACTIVE && readingTracker.snapshot() != null) {
            val packageName = AccessibilityHelperService.instance?.currentPackageName().orEmpty()
            if (packageName.isNotBlank() && readingTracker.pauseIfContextChanged(ScreenCaptureService.session.sessionId, packageName)) {
                pendingActionAfterLocalSpeech = null
                voiceLog("ARTICLE_SCROLL_REJECTED reading_session_id=${readingTracker.snapshot()?.readingSessionId} reason=foreground_context_changed package=$packageName")
            }
        }
        if (state != ScreenShareState.ACTIVE && readingTracker.snapshot()?.state in setOf(
                ReadingState.READING, ReadingState.WAITING_FOR_SCROLL,
                ReadingState.SCROLLING, ReadingState.VERIFYING_NEW_CONTENT
            )) {
            stopArticleReading("media_projection_disconnected", "Screen sharing stopped, isliye reading rok di.")
        }
        if (state != ScreenShareState.ACTIVE) {
            screenActionRegistry.cancel()?.let {
                voiceLog("SCREEN_ACTION_CANCELLED actionId=${it.actionId} turnId=${it.turnId} reason=screen_session_inactive")
            }
        }
        if (state != ScreenShareState.ACTIVE && screenResponseActive) {
            voiceLog("screen_query_result_dropped_stale screen_query_id=$screenResponseQueryId screen_session_id=$screenResponseSessionId state=$state")
            screenResponseActive = false
            screenResponseSessionId = ""
            screenResponseQueryId = ""
            output.clear()
            audio?.interrupt()
        }
        if (state == ScreenShareState.ACTIVE && frame != null &&
            screenVisionPreferences.visionEnabled && isNaturalVoiceReady
        ) {
            val record = ScreenCaptureService.currentFrame()
            if (record?.source != "explicit_query") {
                live?.sendScreenFrame(frame)
                voiceLog("screen_frame_routed bytes=${frame.size} source=media_projection frame_id=${record?.frameId ?: 0L} temporary=true")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting LYRA…"))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LYRA:BackgroundVoice")
            .apply { setReferenceCounted(false); acquire() }
        isRunning = true
        ScreenCaptureService.listeners += screenCaptureListener
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
        if (hasGreeted) voiceLog(
            "GEMINI_RECONNECTING timestamp=${android.os.SystemClock.elapsedRealtime()} media_projection_state=${ScreenCaptureService.currentState}"
        )
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
                voiceLog("GEMINI_CONNECTED timestamp=${android.os.SystemClock.elapsedRealtime()} media_projection_state=${ScreenCaptureService.currentState}")
                isNaturalVoiceReady = true
                audio?.start()
                if (screenVisionPreferences.visionEnabled && ScreenCaptureService.hasFreshFrame()) {
                    ScreenCaptureService.latestFrame?.let(client::sendScreenFrame)
                }
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
            client.onAudio = { pcm, modelGenerationId ->
                val audioReceivedAt = android.os.SystemClock.elapsedRealtime()
                latestObservedModelGenerationId = maxOf(latestObservedModelGenerationId, modelGenerationId)
                voiceLog(
                    "service_audio_received bytes=${pcm.size} modelGenerationId=$modelGenerationId validating=${validatingLocalSpeech != null} " +
                        "streaming=$localSpeechStreamedDirectly suppressed=$suppressModelForTurn " +
                        "localSpeaking=$localAudioSpeaking"
                )
                if (validatingLocalSpeech != null) {
                    localSpeechHasContent = true
                    if (localSpeechFirstAudioReceivedAt == 0L) {
                        localSpeechFirstAudioReceivedAt = audioReceivedAt
                        voiceLog(
                            "controlled_first_audio_received turnId=${responseArbiter.turnId} generationId=$controlledGenerationId " +
                                "firstAudioReceivedAt=$audioReceivedAt requestToFirstAudioMs=${audioReceivedAt - localSpeechRequestSentAt}"
                        )
                        if (instantScreenQueryId.isNotBlank()) {
                            voiceLog(
                                "TOTAL_SCREEN_RESPONSE screenQueryId=$instantScreenQueryId route=HOT_SCREEN_CACHE " +
                                    "voice_ms=-1 capture_ms=0 accessibility_ms=0 vision_ms=0 gemini_ms=${audioReceivedAt - localSpeechRequestSentAt} " +
                                    "tts_ms=${audioReceivedAt - localSpeechRequestSentAt} total_ms=${audioReceivedAt - instantScreenQueryStartedAt} " +
                                    "frame_age_ms=$instantScreenCacheAgeMs"
                            )
                            instantScreenQueryId = ""
                        }
                    }
                    localSpeechLastAudioReceivedAt = audioReceivedAt
                    if (localSpeechStreamedDirectly) {
                        // The transcript prefix already matched the prepared response.
                        // Continue streaming the remaining natural voice without waiting
                        // for the complete sentence.
                        audio?.queueAudio(pcm)
                        voiceLog("service_audio_routed route=direct_playback bytes=${pcm.size}")
                    } else {
                        localSpeechAudio += pcm.copyOf()
                        voiceLog(
                            "service_audio_routed route=validation_buffer bytes=${pcm.size} " +
                                "bufferChunks=${localSpeechAudio.size}"
                        )
                        startLocalSpeechWhenPrefixMatches()
                    }
                }
                else if (screenResponseActive && ScreenCaptureService.session.isCurrent(screenResponseSessionId)) {
                    val generationAccepted = screenResponseBinding?.acceptsGeneration(modelGenerationId) == true
                    screenResponseGenerationId = screenResponseBinding?.screenGenerationId ?: 0L
                    if (!generationAccepted) {
                        voiceLog("screen_query_result_dropped_stale screen_query_id=$screenResponseQueryId modelGenerationId=$modelGenerationId expectedAfter=$screenResponseAfterGenerationId boundGenerationId=$screenResponseGenerationId")
                    } else {
                        if (!screenResponseStartedLogged) {
                            screenResponseStartedLogged = true
                            voiceLog("screen_query_state screenQueryId=$screenResponseQueryId state=RESPONSE_STARTED source=AUDIO modelGenerationId=$modelGenerationId")
                        }
                        screenResponseHasContent = true
                        mediaGuard.beginAssistantTurn()
                        audio?.setPlaybackContext(modelGenerationId, screenResponseQueryId, "CONTROLLED_SCREEN")
                        audio?.setBargeInEnabled(true)
                        audio?.queueAudio(pcm)
                        voiceLog(
                            "route_decision turnId=$screenResponseUserTurnId modelGenerationId=$modelGenerationId responseOwner=CONTROLLED_SCREEN " +
                                "screen_query_id=$screenResponseQueryId route=screen_response accepted=true firstResponseAudioAt=$audioReceivedAt " +
                                "geminiSendToFirstResponseMs=${if (screenFrameSentAt > 0L) audioReceivedAt - screenFrameSentAt else -1L} " +
                                "speechEndToFirstAudibleMs=${if (screenQuerySpeechTurnConsistency) audioReceivedAt - screenResponseSpeechEndedAt else -1L} " +
                                "screen_response_turn_consistency=${screenResponseUserTurnId != 0L} " +
                                "screenQuerySpeechTurnConsistency=$screenQuerySpeechTurnConsistency"
                        )
                    }
                }
                else if (responseArbiter.acceptsOrdinaryModel() && LyraPlaybackCapturePolicy.shouldAcceptModelAudio(
                        suppressed = suppressModelForTurn,
                        assistantAlreadySpeaking = localAudioSpeaking,
                        mediaGuardAllowsResponse = mediaGuard.allowModelResponse()
                    )
                ) {
                    // Capturable LYRA speech uses USAGE_MEDIA. Once the first valid
                    // chunk is accepted, keep Media Guard awake so LYRA never mistakes
                    // her own active AudioTrack for external YouTube playback.
                    when (val decision = ordinaryModelAudioGate.decide(modelGenerationId)) {
                        ModelAudioDecision.ACCEPT -> {
                            acceptedModelGenerationForTurn = modelGenerationId
                            mediaGuard.beginAssistantTurn()
                            audio?.setPlaybackContext(modelGenerationId, responseOwner = "MODEL")
                            audio?.setBargeInEnabled(true)
                            audio?.queueAudio(pcm)
                            voiceLog(
                                "route_decision turnId=$activeTurnId modelGenerationId=$modelGenerationId responseOwner=MODEL " +
                                    "route=ordinary_model accepted=true firstModelAudioAcceptedAt=$audioReceivedAt " +
                                    "speechEndToFirstAcceptedModelAudioMs=${if (speechActivityEndedAt > 0L) audioReceivedAt - speechActivityEndedAt else -1L} bytes=${pcm.size}"
                            )
                        }
                        ModelAudioDecision.BUFFER_UNTIL_SPEECH_END -> {
                            if (earlyModelAudioGenerationId != 0L && earlyModelAudioGenerationId != modelGenerationId) {
                                modelAudioDroppedBeforeTurnCompleteCount += earlyModelAudio.size
                                modelAudioDroppedBeforeTurnCompleteBytes += earlyModelAudioBytes
                                earlyModelAudio.clear()
                                earlyModelAudioBytes = 0L
                            }
                            earlyModelAudioGenerationId = modelGenerationId
                            earlyModelAudio += pcm.copyOf()
                            earlyModelAudioBytes += pcm.size
                            voiceLog(
                                "route_decision turnId=$activeTurnId modelGenerationId=$modelGenerationId responseOwner=MODEL " +
                                    "route=ordinary_model accepted=false rejectionReason=user_speech_active userSpeechActive=true " +
                                    "earlyModelAudioBufferedCount=${earlyModelAudio.size} earlyModelAudioBufferedBytes=$earlyModelAudioBytes"
                            )
                        }
                        else -> {
                            modelAudioDroppedBeforeTurnCompleteCount++
                            modelAudioDroppedBeforeTurnCompleteBytes += pcm.size
                            voiceLog(
                                "route_decision turnId=$activeTurnId modelGenerationId=$modelGenerationId responseOwner=MODEL " +
                                    "route=ordinary_model accepted=false rejectionReason=$decision staleGeneration=${decision == ModelAudioDecision.DROP_STALE_GENERATION} " +
                                    "modelAudioBufferedBeforeTurnCompleteCount=0 modelAudioBufferedBeforeTurnCompleteBytes=0 " +
                                    "modelAudioDroppedBeforeTurnCompleteCount=$modelAudioDroppedBeforeTurnCompleteCount " +
                                    "modelAudioDroppedBeforeTurnCompleteBytes=$modelAudioDroppedBeforeTurnCompleteBytes bytes=${pcm.size}"
                            )
                        }
                    }
                } else {
                    voiceLog("duplicate_response_prevented turnId=${responseArbiter.turnId} modelGenerationId=$modelGenerationId responseOwner=${responseArbiter.owner} route=ordinary_model bytes=${pcm.size}")
                }
            }
            client.onInterrupted = { modelGenerationId ->
                if (validatingLocalSpeech == null && responseArbiter.acceptsOrdinaryModel()) {
                    ordinaryModelAudioGate.cancelGeneration(modelGenerationId)
                    audio?.interrupt()
                    voiceLog(
                        "playback_cancelled_by_barge_in turnId=$activeTurnId modelGenerationId=$modelGenerationId " +
                            "playbackCancelledByBargeIn=true cancelledGenerationId=$modelGenerationId speechActivityStartedAt=$speechActivityStartedAt"
                    )
                } else voiceLog(
                    "interrupted_event_ignored turnId=$activeTurnId modelGenerationId=$modelGenerationId " +
                        "reason=controlled_owner responseOwner=${responseArbiter.owner}"
                )
            }
            client.onGenerationComplete = { modelGenerationId ->
                voiceLog("model_generation_complete turnId=$activeTurnId modelGenerationId=$modelGenerationId at=${android.os.SystemClock.elapsedRealtime()}")
            }
            client.onInputTranscript = inputTranscript@ { part, latestModelGenerationId ->
                if (screenResponseActive) {
                    if (earlyScreenQueryAwaitingFinalTranscript) {
                        appendTranscript(input, part)
                        appendTranscript(commandProbe, part)
                        voiceLog("screen_query_final_transcript_collecting screen_query_id=$screenResponseQueryId userTurnId=$screenResponseUserTurnId textChars=${part.length}")
                        return@inputTranscript
                    }
                    voiceLog(
                        "screen_response_input_ignored screen_query_id=$screenResponseQueryId " +
                            "userTurnId=$screenResponseUserTurnId reason=no_confirmed_real_barge_in textChars=${part.length}"
                    )
                    return@inputTranscript
                }
                if (input.isEmpty()) {
                    activeTurnId = ++turnSequence
                    inputTurnStartedAt = android.os.SystemClock.elapsedRealtime()
                    if (speechTimingTurnId == 0L && speechActivityStartedAt > 0L) speechTimingTurnId = activeTurnId
                    responseArbiter.begin(activeTurnId)
                    acceptedModelGenerationForTurn = 0L
                    modelAudioDroppedBeforeTurnCompleteCount = 0
                    modelAudioDroppedBeforeTurnCompleteBytes = 0L
                    voiceLog("input_turn_started turnId=$activeTurnId session=${hashCode()} inputTurnStartedAt=$inputTurnStartedAt speechActivityStartedAt=$speechActivityStartedAt latestModelGenerationId=$latestModelGenerationId")
                    if (pendingBestFriendCorrectionOldName != null &&
                        android.os.SystemClock.elapsedRealtime() <= pendingBestFriendCorrectionUntil
                    ) {
                        // Reserve a pending clarification turn before Gemini can emit an
                        // ordinary acknowledgement. Ownership becomes CONTROLLED_LOCAL
                        // when the validated clarification reply is queued.
                        suppressModelForTurn = true
                        output.clear()
                        voiceLog(
                            "pending_correction_turn_reserved turnId=$activeTurnId " +
                                "databaseMutationAllowed=false successAcknowledgementAllowed=false"
                        )
                    }
                }
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
                        val earlyScreenText = romanDisplayText(commandProbe.toString())
                        if (ScreenVisionIntentParser.parseStableQuery(earlyScreenText) != null) {
                            audio?.confirmMediaSpeechFromTranscript(earlyScreenText)
                            mediaBlockedTurn = false
                            suppressModelForTurn = true
                            output.clear()
                            input.clear(); input.append(commandProbe)
                            armScreenQuestion(earlyScreenText, activeTurnId, "MEDIA_PARTIAL_COMMAND")
                            return@inputTranscript
                        }
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
                            audio?.confirmMediaSpeechFromTranscript(commandProbe.toString())
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
                                commitFinalUserMessage(spoken, "DIRECT_MEDIA_COMMAND")
                                commandUserTextEmitted = true
                            }
                            mediaBlockedTurn = false
                            executeCommand(directCommand)
                            return@inputTranscript
                        }
                        val coherentMediaSpeech = romanDisplayText(commandProbe.toString())
                        if (MediaSpeechCoherencePolicy.isCoherent(coherentMediaSpeech) &&
                            audio?.confirmMediaSpeechFromTranscript(coherentMediaSpeech) == true
                        ) {
                            // A coherent ASR result backed by the active near-field VAD
                            // candidate is real user speech, even when it is ordinary
                            // conversation rather than a screen/device command.
                            mediaGuard.confirmUserSpeech()
                            mediaBlockedTurn = false
                            suppressModelForTurn = false
                            input.clear()
                            input.append(commandProbe)
                            voiceLog(
                                "media_candidate_promoted reason=coherent_conversation candidateTextChars=${coherentMediaSpeech.length} " +
                                    "userTurnId=$activeTurnId responseOwner=MODEL"
                            )
                        } else {
                            if (CommandParser.isProbableDeviceAction(part) || CommandParser.isProbableDeviceAction(commandProbe.toString())) {
                                probableActionTurn = true
                                suppressModelForTurn = true
                                output.clear()
                            }
                            if (!mediaBlockedTurn) emitState("Media Guard active — listening for your voice")
                            mediaBlockedTurn = true
                            output.clear()
                            return@inputTranscript
                        }
                    }
                    HandsFreeMediaGuard.Gate.WAKE_DETECTED -> {
                        audio?.confirmMediaSpeechFromTranscript(part)
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
                    // Fresh mic input must not steal a turn still owned by a controlled
                    // Gemini generation; its late model text/audio remains suppressed.
                    suppressModelForTurn = !responseArbiter.acceptsOrdinaryModel()
                    localCommandExecutedThisTurn = false
                }
                if (pendingBestFriendCorrectionOldName != null &&
                    android.os.SystemClock.elapsedRealtime() <= pendingBestFriendCorrectionUntil
                ) {
                    // Media-guard and fresh-input state transitions above may normally
                    // re-enable MODEL output. A pending correction must remain reserved.
                    suppressModelForTurn = true
                    output.clear()
                }
                appendTranscript(input, part); appendTranscript(commandProbe, part)
                lastUserIntentText = input.toString().trim()
                val currentTranscript = commandProbe.toString().trim()
                val currentScreenText = romanDisplayText(currentTranscript)
                if (ScreenVisionIntentParser.parse(currentScreenText) != null) {
                    // A screen turn is answered only after an explicitly bound fresh
                    // capture. Stop speculative ordinary output from becoming a second
                    // answer before the FINAL turn boundary arrives.
                    suppressModelForTurn = true
                    output.clear()
                    audio?.interrupt()
                    if (ScreenVisionIntentParser.parseStableQuery(currentScreenText) != null) {
                        armScreenQuestion(currentScreenText, activeTurnId, "PARTIAL_SCREEN_QUERY")
                    }
                }
                val plausibilityPreview = transcriptPlausibilityGate.preview(currentTranscript)
                if (!plausibilityPreview.semanticProcessingAllowed) {
                    // Stop speculative MODEL output as soon as an unrelated dominant
                    // script appears. The immutable FINAL transcript makes the decision.
                    suppressModelForTurn = true
                    output.clear()
                    audio?.interrupt()
                    voiceLog(
                        "input_transcript_plausibility_preview raw=${currentTranscript.take(120)} " +
                            "dominantScript=${plausibilityPreview.dominantScript} " +
                            "transcriptPlausibility=${plausibilityPreview.transcriptPlausibility} " +
                            "anomalyReason=${plausibilityPreview.anomalyReason}"
                    )
                    // Keep collecting raw chunks for the authoritative FINAL decision,
                    // but do not let partial foreign-script text reach memory, correction,
                    // delete, command, or permission parsers.
                    return@inputTranscript
                }
                val detectedPersonalMemory =
                    PersonalMemoryExtractor.extract(romanDisplayText(currentTranscript))
                if (detectedPersonalMemory != null) {
                    if (MemoryRelationshipPolicy.isBestFriend(detectedPersonalMemory) ||
                        MemorySafetyPolicy.decide(detectedPersonalMemory) == MemorySaveDecision.AUTO_SAVE
                    ) {
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
                if (BestFriendNameCorrectionParser.needsClearCorrectedName(romanMemoryTranscript)) {
                    suppressModelForTurn = true
                    output.clear()
                    audio?.interrupt()
                }
                if (UnclearDeleteIntentGuard.needsClarification(romanMemoryTranscript)) {
                    // Never let a garbled delete phrase reach Gemini as an invitation
                    // to guess that the user wants an app uninstalled.
                    suppressModelForTurn = true
                    output.clear()
                    audio?.interrupt()
                }
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
                        commitFinalUserMessage(spoken, "PARTIAL_COMMAND")
                        commandUserTextEmitted = true
                    }
                    executeCommand(command)
                }
            }
            client.onOutputTranscript = { transcript, modelGenerationId ->
                if (validatingLocalSpeech != null) {
                    localSpeechHasContent = true
                    appendTranscript(localSpeechTranscript, transcript)
                    startLocalSpeechWhenPrefixMatches()
                }
                else if (screenResponseActive && ScreenCaptureService.session.isCurrent(screenResponseSessionId)) {
                    if (screenResponseBinding?.acceptsGeneration(modelGenerationId) == true) {
                        screenResponseGenerationId = screenResponseBinding?.screenGenerationId ?: 0L
                        if (!screenResponseStartedLogged) {
                            screenResponseStartedLogged = true
                            voiceLog("screen_query_state screenQueryId=$screenResponseQueryId state=RESPONSE_STARTED source=TEXT modelGenerationId=$modelGenerationId")
                        }
                        screenResponseHasContent = true
                        appendTranscript(output, transcript)
                        voiceLog("screen_query_result_received screen_query_id=$screenResponseQueryId screen_session_id=$screenResponseSessionId userTurnId=$screenResponseUserTurnId modelGenerationId=$modelGenerationId firstResponseTextAt=${android.os.SystemClock.elapsedRealtime()} screen_response_turn_consistency=true")
                    } else voiceLog("screen_query_result_dropped_stale screen_query_id=$screenResponseQueryId modelGenerationId=$modelGenerationId reason=wrong_generation")
                }
                else if (responseArbiter.acceptsOrdinaryModel() && !suppressModelForTurn &&
                    !hideNextModelTranscript && mediaGuard.allowModelResponse()
                ) appendTranscript(output, transcript)
                else voiceLog("duplicate_response_prevented turnId=${responseArbiter.turnId} responseOwner=${responseArbiter.owner} route=ordinary_model_text")
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
                        }, LOCAL_SPEECH_AUDIO_DRAIN_MS)
                    }
                    responseArbiter.controlledGenerationComplete()
                    val turnCompleteAt = android.os.SystemClock.elapsedRealtime()
                    voiceLog("turn_complete_received turnId=${responseArbiter.turnId} generationId=$controlledGenerationId responseOwner=${responseArbiter.owner} turnCompleteAt=$turnCompleteAt lastAudioReceivedAt=$localSpeechLastAudioReceivedAt")
                    resetTurnBuffers("controlled_generation_complete")
                    return@turnComplete
                }
                if (screenResponseActive) {
                    if (earlyScreenQueryAwaitingFinalTranscript && input.isNotBlank()) {
                        val rawFinal = input.toString().trim()
                        val finalDisplay = finalTranscriptDisplay(rawFinal)
                        val semantic = FinalSemanticUserUtterance.from(
                            transcriptSessionId, screenResponseUserTurnId, rawFinal, finalDisplay
                        )
                        commitFinalUserMessage(rawFinal, "TURN_COMPLETE_EARLY_SCREEN_QUERY", semantic.canonicalSemanticText, semantic.displayText)
                        earlyScreenQueryAwaitingFinalTranscript = false
                        input.clear(); commandProbe.clear()
                        voiceLog("screen_query_final_transcript_committed screen_query_id=$screenResponseQueryId userTurnId=$screenResponseUserTurnId")
                        if (!screenResponseHasContent) return@turnComplete
                    }
                    if (!screenResponseHasContent) {
                        voiceLog("screen_response_empty_boundary_ignored screen_query_id=$screenResponseQueryId")
                        return@turnComplete
                    }
                    val current = ScreenCaptureService.session.isCurrent(screenResponseSessionId)
                    val text = output.toString().trim()
                    if (current && text.isNotBlank() && !screenResponseTextCommitted) {
                        listener?.onMyraText(romanDisplayText(text))
                        com.myra.assistant.screen.ScreenContextStore.onAnalysis(
                            text, android.os.SystemClock.elapsedRealtime()
                        )
                        screenResponseTextCommitted = true
                    }
                    else voiceLog("screen_query_result_dropped_stale screen_query_id=$screenResponseQueryId screen_session_id=$screenResponseSessionId reason=${if (!current) "stopped_session" else "empty_result"}")
                    voiceLog("screen_response_generation_complete screen_query_id=$screenResponseQueryId screen_session_id=$screenResponseSessionId current=$current")
                    voiceLog(
                        "VISION_REQUEST_COMPLETED screenQueryId=$screenResponseQueryId screen_session_id=$screenResponseSessionId " +
                            "timestamp=${android.os.SystemClock.elapsedRealtime()} visionLatencyMs=${(android.os.SystemClock.elapsedRealtime() - screenFrameSentAt).coerceAtLeast(0L)} " +
                            "totalLatencyMs=${if (screenQuerySpeechTurnConsistency) (android.os.SystemClock.elapsedRealtime() - screenResponseSpeechEndedAt).coerceAtLeast(0L) else -1L}"
                    )
                    screenResponseGenerationComplete = true
                    if (!localAudioSpeaking) finishScreenResponse("generation_complete_no_playback")
                    else output.clear()
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
                    val blockedText = romanDisplayText(commandProbe.toString().trim())
                    if (ScreenVisionIntentParser.parseStableQuery(blockedText) != null) {
                        mediaBlockedTurn = false
                        audio?.confirmMediaSpeechFromTranscript(blockedText)
                        if (blockedText.isNotBlank() && !commandUserTextEmitted) {
                            commitFinalUserMessage(blockedText, "MEDIA_CONFIRMED_SCREEN_QUERY")
                            commandUserTextEmitted = true
                        }
                        voiceLog("media_candidate_promoted reason=validated_screen_query commandChars=${commandProbe.length} userTurnId=$activeTurnId")
                        beginFreshScreenQuery(blockedText, activeTurnId)
                        resetTurnBuffers("media_confirmed_screen_query")
                        waitingForFreshInputAfterCommand = true
                        return@turnComplete
                    }
                    if (MediaSpeechCoherencePolicy.isCoherent(blockedText) &&
                        audio?.confirmMediaSpeechFromTranscript(blockedText) == true
                    ) {
                        val promotedTurnId = activeTurnId
                        mediaBlockedTurn = false
                        mediaGuard.confirmUserSpeech()
                        suppressModelForTurn = false
                        if (!commandUserTextEmitted) {
                            commitFinalUserMessage(blockedText, "MEDIA_CONFIRMED_CONVERSATION")
                            commandUserTextEmitted = true
                        }
                        voiceLog(
                            "media_candidate_promoted reason=coherent_conversation_at_boundary " +
                                "commandChars=${commandProbe.length} userTurnId=$promotedTurnId responseOwner=MODEL"
                        )
                        resetTurnBuffers("media_confirmed_conversation")
                        // The speculative reply may already have been suppressed while
                        // classification was pending. Ask the same Live session for one
                        // ordinary natural response; do not create a local/TTS path.
                        responseArbiter.begin(promotedTurnId)
                        live?.sendText(blockedText)
                        return@turnComplete
                    }
                    mediaBlockedTurn = false
                    resetTurnBuffers("media_blocked_turn_complete")
                    return@turnComplete
                }
                if (hideNextModelTranscript) {
                    hideNextModelTranscript = false
                    resetTurnBuffers("hidden_model_turn_complete")
                    waitingForFreshInputAfterCommand = true
                    return@turnComplete
                }
                mainHandler.removeCallbacks(memoryCommandRunnable)
                pendingMemoryCommand = null
                mainHandler.removeCallbacks(personalMemoryPauseRunnable)
                pendingDetectedPersonalMemory = null
                val accumulatorBeforeFinal = input.toString().trim()
                val duplicateResult = FinalTranscriptDuplicateGuard.collapse(accumulatorBeforeFinal)
                val userText = duplicateResult.text
                val myraText = output.toString().trim()
                voiceLog(
                    "final_transcript_duplicate_guard mediaCandidateId=${audio?.currentMediaCandidateId() ?: 0L} " +
                        "candidateTranscript=${commandProbe.toString().trim().take(160)} " +
                        "finalGeminiTranscript=${accumulatorBeforeFinal.take(160)} " +
                        "accumulatorBeforeFinal=${accumulatorBeforeFinal.take(160)} " +
                        "duplicateFinalDetected=${duplicateResult.duplicateDetected} " +
                        "duplicateCollapseApplied=${duplicateResult.collapseApplied} " +
                        "collapseReason=${duplicateResult.reason} finalDisplayText=${userText.take(160)}"
                )
                val finalInputTranscriptAt = android.os.SystemClock.elapsedRealtime()
                val plausibility = transcriptPlausibilityGate.assessFinal(userText)
                val plausibilityTurnId = activeTurnId.takeIf { it != 0L }
                    ?: responseArbiter.turnId.takeIf { it != 0L }
                    ?: ++turnSequence
                val plausibilityUtteranceId = "$transcriptSessionId:$plausibilityTurnId"
                voiceLog(
                    "final_transcript_plausibility utteranceId=$plausibilityUtteranceId " +
                        "rawGeminiTranscript=${userText.take(160)} " +
                        "detectedScripts=${plausibility.detectedScripts} " +
                        "dominantScript=${plausibility.dominantScript} " +
                        "recentSessionLanguageProfile=${plausibility.recentSessionLanguageProfile} " +
                        "transcriptPlausibility=${plausibility.transcriptPlausibility} " +
                        "anomalyReason=${plausibility.anomalyReason} " +
                        "semanticProcessingAllowed=${plausibility.semanticProcessingAllowed} " +
                        "userBubbleCommitAllowed=${plausibility.userBubbleCommitAllowed} " +
                        "memoryMutationAllowed=${plausibility.memoryMutationAllowed}"
                )
                if (!plausibility.semanticProcessingAllowed) {
                    suppressModelForTurn = true
                    localCommandExecutedThisTurn = true
                    output.clear(); audio?.interrupt()
                    listener?.onMyraText(FinalTranscriptPlausibilityGate.CLARIFICATION_REPLY)
                    emitState(FinalTranscriptPlausibilityGate.CLARIFICATION_REPLY)
                    queueLocalSpeech(
                        FinalTranscriptPlausibilityGate.CLARIFICATION_REPLY,
                        allowUntranscribedAudio = true
                    )
                    resetTurnBuffers("suspicious_final_transcript")
                    waitingForFreshInputAfterCommand = true
                    return@turnComplete
                }
                val finalDisplay = finalTranscriptDisplay(userText)
                val finalUtterance = FinalSemanticUserUtterance.from(
                    sessionId = transcriptSessionId,
                    turnId = plausibilityTurnId,
                    rawGeminiTranscript = userText,
                    formatted = finalDisplay
                )
                val normalizedFinalUserText = finalUtterance.canonicalSemanticText
                val displayedFinalUserText = finalUtterance.displayText
                voiceLog(
                    "final_input_transcript raw=${userText.take(160)} " +
                        "normalized=${normalizedFinalUserText.take(160)} " +
                        "display=${displayedFinalUserText.take(160)} finalInputTranscriptAt=$finalInputTranscriptAt"
                )
                voiceLog(
                    "final_transcript_display turnId=$activeTurnId utteranceId=${transcriptSessionId}:$activeTurnId " +
                        "raw=${userText.take(160)} transliterated=${finalDisplay.transliterated.take(160)} " +
                        "display=${displayedFinalUserText.take(160)} " +
                        "latinWordsPreserved=${finalDisplay.latinWordsPreserved} " +
                        "properNameProtected=${finalDisplay.properNameProtected} " +
                        "ruleIds=${finalDisplay.appliedRuleIds.joinToString(",")}"
                )
                voiceLog(
                    "final_semantic_utterance utteranceId=${finalUtterance.utteranceId} " +
                        "rawGeminiTranscript=${userText.take(160)} " +
                        "canonicalSemanticText=${normalizedFinalUserText.take(160)} " +
                        "displayText=${displayedFinalUserText.take(160)} " +
                        "canonicalNameTokens=${finalUtterance.canonicalNameTokens} " +
                        "displayNameTokens=${finalUtterance.displayNameTokens} " +
                        "memoryExtractorInput=${finalUtterance.memoryExtractorInput.take(160)} " +
                        "correctionParserInput=${finalUtterance.correctionParserInput.take(160)} " +
                        "deleteParserInput=${finalUtterance.deleteParserInput.take(160)} " +
                        "clarificationResolverInput=${finalUtterance.clarificationResolverInput.take(160)} " +
                        "semanticConsistency=${finalUtterance.semanticConsistency}"
                )
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
                if (userText.isNotBlank() && !commandUserTextEmitted) {
                    commitFinalUserMessage(
                        raw = userText,
                        source = "TURN_COMPLETE",
                        normalized = normalizedFinalUserText,
                        display = displayedFinalUserText
                    )
                }
                val brainDecision = brain.interpret(normalizedFinalUserText)
                voiceLog(
                    "brain_decision turnId=$activeTurnId intent=${LyraBrainCoordinator.classify(normalizedFinalUserText)} " +
                        "decision=${brainDecision.javaClass.simpleName} state=${brain.snapshot()}"
                )
                val readingCommand = ReadingIntentParser.parse(normalizedFinalUserText)
                if (readingCommand != null && handleReadingCommand(readingCommand, activeTurnId)) {
                    resetTurnBuffers("reading_command")
                    waitingForFreshInputAfterCommand = true
                    return@turnComplete
                }
                when (brainDecision) {
                    is BrainDecision.Cancel -> {
                        handleBrainCancellation(brainDecision.taskToken)
                        resetTurnBuffers("brain_task_cancelled")
                        waitingForFreshInputAfterCommand = true
                        return@turnComplete
                    }
                    is BrainDecision.ScrollThenOpenVideo -> {
                        if (!screenCommandTurnGuard.tryCommit(activeTurnId)) {
                            voiceLog("screen_command_duplicate_dropped turnId=$activeTurnId decision=ScrollThenOpenVideo")
                            resetTurnBuffers("duplicate_screen_command")
                            return@turnComplete
                        }
                        screenActionRegistry.cancel()?.let {
                            voiceLog("SCREEN_ACTION_CANCELLED actionId=${it.actionId} turnId=${it.turnId} reason=new_multi_step_command")
                        }
                        executeBrainMultiStep(brainDecision)
                        resetTurnBuffers("brain_multi_step_started")
                        waitingForFreshInputAfterCommand = true
                        return@turnComplete
                    }
                    is BrainDecision.ScreenAction -> {
                        if (!screenCommandTurnGuard.tryCommit(activeTurnId)) {
                            voiceLog("screen_command_duplicate_dropped turnId=$activeTurnId decision=ScreenAction")
                            resetTurnBuffers("duplicate_screen_command")
                            return@turnComplete
                        }
                        screenActionRegistry.cancel()?.let {
                            voiceLog("SCREEN_ACTION_CANCELLED actionId=${it.actionId} turnId=${it.turnId} reason=new_contextual_command")
                        }
                        executeContextualScreenAction(brainDecision.target)
                        resetTurnBuffers("brain_contextual_screen_action")
                        waitingForFreshInputAfterCommand = true
                        return@turnComplete
                    }
                    is BrainDecision.Clarify -> {
                        suppressModelForTurn = true
                        localCommandExecutedThisTurn = true
                        listener?.onMyraText(brainDecision.message)
                        emitState(brainDecision.message)
                        queueLocalSpeech(brainDecision.message, allowUntranscribedAudio = true)
                        resetTurnBuffers("brain_reference_clarification")
                        waitingForFreshInputAfterCommand = true
                        return@turnComplete
                    }
                    BrainDecision.PassThrough -> Unit
                }
                val screenIntent = ScreenVisionIntentParser.parse(normalizedFinalUserText)
                if (screenIntent != null) {
                    if (ScreenQueryDispatchPolicy.shouldDispatch(
                            screenResponseActive, earlyScreenQueryDispatchedTurnId, activeTurnId
                        )) {
                        beginFreshScreenQuery(normalizedFinalUserText, activeTurnId)
                    }
                    resetTurnBuffers("screen_query_fresh_capture_requested")
                    waitingForFreshInputAfterCommand = true
                    return@turnComplete
                }
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
                    val displayText = normalizedFinalUserText
                    val pendingCorrectionOld = pendingBestFriendCorrectionOldName?.takeIf {
                        android.os.SystemClock.elapsedRealtime() <= pendingBestFriendCorrectionUntil
                    }
                    if (pendingCorrectionOld != null) {
                        val confirmationName = pendingSpellingConfirmationName
                        if (confirmationName != null && normalizeSpeech(displayText) in setOf("haan", "han", "yes")) {
                            pendingSpellingConfirmationName = null
                            pendingBestFriendCorrectionOldName = null
                            pendingBestFriendCorrectionUntil = 0L
                            startCanonicalRename(BestFriendNameCorrection(pendingCorrectionOld, confirmationName))
                            resetTurnBuffers("spelling_confirmed")
                            waitingForFreshInputAfterCommand = true
                            return@turnComplete
                        }
                        val resolved = ClarifiedPersonNameResolver.resolve(displayText)
                        voiceLog(
                            "correction_clarification pendingType=BEST_FRIEND_RENAME " +
                                "target=$pendingCorrectionOld raw=${userText.take(100)} " +
                                "normalized=${displayText.take(100)} resolved=$resolved"
                        )
                        when (resolved) {
                            is ClarifiedNameResult.Accepted -> {
                                pendingSpellingConfirmationName = null
                                pendingBestFriendCorrectionOldName = null
                                pendingBestFriendCorrectionUntil = 0L
                                startCanonicalRename(BestFriendNameCorrection(pendingCorrectionOld, resolved.name))
                                resetTurnBuffers("clarified_name_accepted")
                                waitingForFreshInputAfterCommand = true
                                return@turnComplete
                            }
                            is ClarifiedNameResult.NeedsConfirmation -> {
                                pendingSpellingConfirmationName = resolved.proposedName
                                val clarification = "Maine ${resolved.heardLetters} suna. Kya naam ${resolved.proposedName} hai?"
                                suppressModelForTurn = true
                                localCommandExecutedThisTurn = true
                                output.clear(); audio?.interrupt()
                                listener?.onMyraText(clarification)
                                emitState(clarification)
                                queueLocalSpeech(clarification, allowUntranscribedAudio = true)
                                resetTurnBuffers("incomplete_spelling_confirmation")
                                waitingForFreshInputAfterCommand = true
                                return@turnComplete
                            }
                            ClarifiedNameResult.Unclear -> {
                                // A pending correction owns this turn. Never let Gemini
                                // improvise a success acknowledgement when no validated
                                // name or verified database transaction exists.
                                val clarification = CorrectionSuccessPolicy.UNRESOLVED_CLARIFICATION_REPLY
                                suppressModelForTurn = true
                                localCommandExecutedThisTurn = true
                                output.clear(); audio?.interrupt()
                                voiceLog(
                                    "correction_clarification_unresolved target=$pendingCorrectionOld " +
                                        "databaseMutationAllowed=false successAcknowledgementAllowed=false"
                                )
                                listener?.onMyraText(clarification)
                                emitState(clarification)
                                queueLocalSpeech(clarification, allowUntranscribedAudio = true)
                                resetTurnBuffers("clarification_unresolved")
                                waitingForFreshInputAfterCommand = true
                                return@turnComplete
                            }
                        }
                    }
                    val pendingDelete = android.os.SystemClock.elapsedRealtime() <=
                        pendingDeleteClarificationUntil
                    val memoryCommand = if (pendingDelete) {
                        PendingDeleteClarification.resolve(displayText)
                            ?: MemoryCommandParser.parse(displayText)
                    } else MemoryCommandParser.parse(displayText)
                    if (memoryCommand != null) {
                        pendingDeleteClarificationUntil = 0L
                        handleMemoryCommand(memoryCommand)
                        resetTurnBuffers()
                        waitingForFreshInputAfterCommand = true
                        return@turnComplete
                    }
                    if (BestFriendNameCorrectionParser.needsClearCorrectedName(finalUtterance.correctionParserInput)) {
                        val clarification = "Correct naam clear nahi hua. Ek baar spelling ya naam clearly repeat karo."
                        localCommandExecutedThisTurn = true
                        suppressModelForTurn = true
                        output.clear()
                        audio?.interrupt()
                        val recentName = lastSavedBestFriendName?.takeIf {
                            android.os.SystemClock.elapsedRealtime() - lastSavedBestFriendAt <=
                                BEST_FRIEND_CORRECTION_CONTEXT_MS
                        }
                        pendingBestFriendCorrectionOldName =
                            BestFriendNameCorrectionParser.ambiguousOldName(displayText, recentName)
                        pendingBestFriendCorrectionUntil = android.os.SystemClock.elapsedRealtime() +
                            BEST_FRIEND_CORRECTION_CONTEXT_MS
                        voiceLog(
                            "correction_clarification_set type=BEST_FRIEND_RENAME " +
                                "target=${pendingBestFriendCorrectionOldName} raw=${userText.take(100)} " +
                                "normalized=${displayText.take(100)}"
                        )
                        listener?.onMyraText(clarification)
                        emitState(clarification)
                        queueLocalSpeech(clarification, allowUntranscribedAudio = true)
                        resetTurnBuffers()
                        waitingForFreshInputAfterCommand = true
                        return@turnComplete
                    }
                    if (UnclearDeleteIntentGuard.needsClarification(finalUtterance.deleteParserInput)) {
                        val clarification = "Kis memory ko delete karna hai? Naam ek baar saaf bol do."
                        localCommandExecutedThisTurn = true
                        suppressModelForTurn = true
                        output.clear()
                        audio?.interrupt()
                        // Keep the question actionable. Previously a one-word reply such
                        // as "Kareem" went to Gemini, which spoke a false success without
                        // ever calling MemoryRepository.forgetMatching().
                        pendingDeleteClarificationUntil = android.os.SystemClock.elapsedRealtime() +
                            DELETE_CLARIFICATION_TIMEOUT_MS
                        listener?.onMyraText(clarification)
                        emitState(clarification)
                        queueLocalSpeech(clarification, allowUntranscribedAudio = true)
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
                    val displayUserText = finalUtterance.memoryExtractorInput
                    val linkedPersonCandidates = PersonLinkedMemoryExtractor.extractAll(displayUserText)
                    val personalCandidate = linkedPersonCandidates.firstOrNull {
                        MemoryRelationshipPolicy.isBestFriend(it)
                    } ?: PersonalMemoryExtractor.extract(displayUserText)
                        ?: contextualRelationshipCandidate(displayUserText)
                    val recentName = lastSavedBestFriendName?.takeIf {
                        android.os.SystemClock.elapsedRealtime() - lastSavedBestFriendAt <=
                            BEST_FRIEND_CORRECTION_CONTEXT_MS
                    }
                    // Parse explicit old->new corrections before the ordinary extractor;
                    // otherwise "Karima nahi, Kareem" becomes a new Kareem row while
                    // the stale Karima row remains active.
                    val correctionDecision = BestFriendNameCorrectionParser.analyze(
                        displayUserText,
                        recentName
                    )
                    val nameCorrection = correctionDecision.correction
                    voiceLog(
                        "name_correction_gate raw=${displayUserText.take(100)} " +
                            "correctionIntentDetected=${correctionDecision.correctionIntentDetected} " +
                            "correctionIntentPattern=${correctionDecision.correctionIntentPattern} " +
                            "oldNameCandidate=${correctionDecision.oldNameCandidate} " +
                            "newNameCandidate=${correctionDecision.newNameCandidate} " +
                            "newNameValidation=${correctionDecision.newNameValidation} " +
                            "rejectionReason=${correctionDecision.rejectionReason} " +
                            "databaseMutationAllowed=${correctionDecision.databaseMutationAllowed}"
                    )
                    if (nameCorrection != null && finalUtterance.semanticConsistency) {
                        // Gemini can conversationally acknowledge a correction even when
                        // Room did not change. Hide that unverified answer and confirm only
                        // after the repository returns and its rows have been read back.
                        startCanonicalRename(nameCorrection)
                    } else if (nameCorrection != null) {
                        val clarification = CorrectionSuccessPolicy.UNRESOLVED_CLARIFICATION_REPLY
                        voiceLog(
                            "name_correction_rejected utteranceId=${finalUtterance.utteranceId} " +
                                "reason=semantic_name_mismatch databaseMutationAllowed=false " +
                                "successAcknowledgementAllowed=false"
                        )
                        suppressModelForTurn = true
                        localCommandExecutedThisTurn = true
                        output.clear(); audio?.interrupt()
                        listener?.onMyraText(clarification)
                        emitState(clarification)
                        queueLocalSpeech(clarification, allowUntranscribedAudio = true)
                        resetTurnBuffers("semantic_name_mismatch")
                        waitingForFreshInputAfterCommand = true
                        return@turnComplete
                    } else if (personalCandidate != null) {
                        recentRelationshipTurns.clear()
                        if (MemoryRelationshipPolicy.isBestFriend(personalCandidate)) {
                            // Explicit completed best-friend statements add that person to
                            // the set silently. They never replace another person implicitly.
                            serviceScope.launch { memoryRepository.saveAdditionalBestFriend(personalCandidate) }
                            rememberBestFriendForCorrection(personalCandidate)
                        } else if (MemorySafetyPolicy.decide(personalCandidate) == MemorySaveDecision.AUTO_SAVE) {
                            serviceScope.launch { memoryRepository.save(personalCandidate) }
                        } else {
                            requestPersonalMemoryPermission(personalCandidate)
                            resetTurnBuffers()
                            waitingForFreshInputAfterCommand = true
                            return@turnComplete
                        }
                    }
                    // One natural sentence can contain more than the relationship.
                    // Persist only additional durable, grounded person facts; temporary
                    // claims such as playing without sleep never enter this list.
                    linkedPersonCandidates
                        .filterNot(MemoryRelationshipPolicy::isBestFriend)
                        .forEach { linkedFact ->
                            serviceScope.launch { memoryRepository.save(linkedFact) }
                        }
                    rememberRecentRelationshipTurn(displayUserText)
                    learnSafePreferenceFromCompletedTurn(userText)
                }
                if (myraText.isNotBlank() && !suppressModelForTurn && responseArbiter.acceptsOrdinaryModel()) {
                    listener?.onMyraText(romanDisplayText(myraText))
                }
                resetTurnBuffers("normal_turn_complete")
                if (suppressModelForTurn) waitingForFreshInputAfterCommand = true
                if (mediaGuard.isAwake()) mediaGuard.finishInteraction()
                pendingLocalSpeech?.let { message ->
                    pendingLocalSpeech = null
                    localSpeechValidationPolicy = pendingLocalSpeechPolicy
                    allowUntranscribedLocalSpeech = pendingLocalSpeechAllowsSilence
                    beginValidatedLocalSpeech(message)
                }
            }
            client.onError = {
                voiceLog("GEMINI_DISCONNECTED timestamp=${android.os.SystemClock.elapsedRealtime()} reason=${it.take(160)} media_projection_state=${ScreenCaptureService.currentState}")
                emitState(it)
            }
            audio?.onMicChunk = { client.sendAudio(it) }
            audio?.onAmplitude = { listener?.onAmplitude(it) }
            audio?.onSpeechActivityChanged = { active ->
                if (active && screenResponseActive) {
                    voiceLog("playback_cancelled_by_real_user responseOwner=CONTROLLED_SCREEN screen_query_id=$screenResponseQueryId vad_trigger_source=local_vad")
                    audio?.interrupt(); live?.interrupt(); finishScreenResponse("real_user_barge_in")
                } else if (active) beginOrdinarySpeechActivity(latestObservedModelGenerationId, "local_vad")
                else {
                    finishOrdinarySpeechActivity()
                    dispatchArmedScreenQuestionAtSpeechEnd()
                }
            }
            audio?.onSpeakingChanged = { speaking ->
                voiceLog(
                    "service_playback_state speaking=$speaking active=$localPlaybackActive " +
                        "generationComplete=$localSpeechGenerationComplete"
                )
                localAudioSpeaking = speaking
                listener?.onSpeaking(speaking)
                updateNotification(if (speaking) "LYRA is speaking" else "LYRA is listening")
                if (!speaking && localPlaybackActive && localSpeechGenerationComplete) {
                    finishLocalPlayback()
                }
                if (!speaking && screenResponseActive && screenResponseGenerationComplete) {
                    finishScreenResponse("playback_end")
                }
            }
            client.connect()
        }
    }

    private suspend fun buildSavedMemoryContext(): String {
        memoryRepository.reconcileUniqueRelationships()
        memoryRepository.reconcilePreferenceDimensions()
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
        // Stop any ordinary model audio queued before the final transcript became a
        // deterministic memory command. The local memory reply must be the only voice.
        cancelSpeechForNewAction()
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
                    is MemoryWriteResult.Saved -> MemoryCommandReplyFormatter.rememberSaved()
                    is MemoryWriteResult.Rejected -> MemoryCommandReplyFormatter.rememberRejected()
                    MemoryWriteResult.NeedsPermission, null -> "Save karne ki permission clear nahi hui."
                }
                is MemoryCommand.Read -> {
                    // Recall must not race a correction write from the previous turn.
                    pendingCanonicalRename?.join()
                    memoryRepository.logActiveBestFriends("before_recall query=${command.query}")
                    val memories = memoryRepository.relevant(command.query, 5)
                    PersonalMemoryRecallFormatter.format(memories.map { it.fact })
                }
                is MemoryCommand.Forget -> {
                    MemoryCommandReplyFormatter.forgotten(
                        memoryRepository.forgetMatching(command.query)
                    )
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
                    "Abhi ${oldName} tumhari best friend saved hai. ${newName} ko replace karun, ya dono ko save karun?"
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
            commitFinalUserMessage(raw, "PERSONAL_MEMORY_CONTEXT_CORRECTION", romanRaw, romanRaw)
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
        commitFinalUserMessage(raw.trim(), "PERSONAL_MEMORY_CONFIRMATION")

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
            val result = if (decision == MemoryConfirmationDecision.ADD) {
                memoryRepository.saveAdditionalBestFriend(candidate)
            } else {
                memoryRepository.save(candidate, permissionGranted = true)
            }
            val message = when (result) {
                is MemoryWriteResult.Saved -> if (decision == MemoryConfirmationDecision.ADD) {
                    "Theek hai, dono ko yaad rakhungi."
                } else {
                    "Theek hai, yaad rakhungi."
                }
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
            "perform_screen_action" -> {
                handleScreenActionTool(id, args)
                return
            }
            "propose_screen_memory" -> {
                handleScreenMemoryProposal(id, args)
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

    private fun handleScreenActionTool(id: String, args: org.json.JSONObject) {
        val intentText = lastUserIntentText.ifBlank { input.toString().trim() }
        screenActionRegistry.cancel()?.let {
            voiceLog("SCREEN_ACTION_CANCELLED actionId=${it.actionId} turnId=${it.turnId} reason=new_explicit_screen_command")
        }
        if (!screenVisionPreferences.visionEnabled || ScreenCaptureService.currentState != ScreenShareState.ACTIVE) {
            live?.sendToolResponse(id, "perform_screen_action", false, "Screen Vision has no current shared frame")
            return
        }
        if (ScreenVisionIntentParser.parse(intentText) == null) {
            live?.sendToolResponse(id, "perform_screen_action", false, "No explicit visible-screen action was requested")
            return
        }
        if (!screenCommandTurnGuard.tryCommit(activeTurnId)) {
            voiceLog("screen_command_duplicate_dropped turnId=$activeTurnId source=perform_screen_action")
            live?.sendToolResponse(id, "perform_screen_action", false, "This screen command was already committed for the current voice turn")
            return
        }
        val toolTarget = args.optString("target_text").trim()
        val toolPosition = args.optString("position").trim().takeIf { it.isNotBlank() && it != "unspecified" }
            ?: when {
                Regex("\\b(?:center|middle|beech)\\b", RegexOption.IGNORE_CASE).containsMatchIn(intentText) -> "center"
                Regex("\\b(?:left|baaye|baye)\\b", RegexOption.IGNORE_CASE).containsMatchIn(intentText) -> "left"
                Regex("\\b(?:right|daaye|daye)\\b", RegexOption.IGNORE_CASE).containsMatchIn(intentText) -> "right"
                Regex("\\b(?:top|upar)\\b", RegexOption.IGNORE_CASE).containsMatchIn(intentText) -> "top"
                Regex("\\b(?:bottom|neeche)\\b", RegexOption.IGNORE_CASE).containsMatchIn(intentText) -> "bottom"
                else -> null
            }
        val explicitTitle = toolTarget.ifBlank {
            intentText.takeIf {
                toolPosition == null && Regex("\\b(?:video|वीडियो)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }.orEmpty()
        }
        val resolvedTarget = brain.resolveScreenTarget(
            explicitTitle,
            toolPosition,
            args.optInt("ordinal", 0)
        )
        if (resolvedTarget == null) {
            live?.sendToolResponse(id, "perform_screen_action", false, "Visible target is ambiguous; ask the user to choose")
            return
        }
        val target = resolvedTarget.targetText
        val position = resolvedTarget.position
        val ordinal = resolvedTarget.ordinal
        val accessibility = AccessibilityHelperService.instance
        if (accessibility == null || !AccessibilityHelperService.isEnabled(this)) {
            live?.sendToolResponse(id, "perform_screen_action", false, "LYRA Accessibility is disabled")
            return
        }
        val contextRequestedAt = android.os.SystemClock.elapsedRealtime()
        val cachedContext = ScreenContextStore.freshSnapshot(
            ScreenCaptureService.session.sessionId, contextRequestedAt, ScreenCacheUse.ACTION
        )
        val cachedFrame = ScreenCaptureService.session.latestFrame?.takeIf {
            cachedContext != null && it.sessionId == cachedContext.screenSessionId &&
                it.frameId == cachedContext.frameId &&
                (contextRequestedAt - it.capturedAt).coerceAtLeast(0L) <= HotScreenCachePolicy.maxAgeMs(
                    cachedContext.currentPackage, cachedContext.lastScrollAt, contextRequestedAt, ScreenCacheUse.ACTION
                )
        }
        if (cachedFrame != null) {
            voiceLog(
                "screen_action_context_ready target=$target source=HOT_SCREEN_CACHE frame_id=${cachedFrame.frameId} " +
                    "frame_age_ms=${(contextRequestedAt - cachedFrame.capturedAt).coerceAtLeast(0L)} contextWaitMs=0"
            )
            performScreenActionTool(id, resolvedTarget, accessibility, cachedFrame)
            return
        }
        val query = ScreenCaptureService.requestFreshFrame(activeTurnId) { result ->
            mainHandler.post {
                val ready = result as? FreshFrameResult.Ready
                if (ready == null || !ScreenCaptureService.session.isCurrent(ready.query.sessionId)) {
                    brain.recordScreenAction(resolvedTarget, false)
                    live?.sendToolResponse(id, "perform_screen_action", false, "A fresh current screen was unavailable")
                    return@post
                }
                val selectedAt = android.os.SystemClock.elapsedRealtime()
                voiceLog(
                    "screen_action_context_ready target=$target screen_query_id=${ready.query.queryId} " +
                        "frame_id=${ready.frame.frameId} frame_age_ms=${(selectedAt - ready.frame.capturedAt).coerceAtLeast(0L)} " +
                        "contextWaitMs=${(selectedAt - contextRequestedAt).coerceAtLeast(0L)}"
                )
                performScreenActionTool(id, resolvedTarget, accessibility, ready.frame)
            }
        }
        if (query == null) live?.sendToolResponse(id, "perform_screen_action", false, "Screen session ended before target resolution")
    }

    private fun performScreenActionTool(
        id: String,
        resolvedTarget: ScreenTargetReference,
        accessibility: AccessibilityHelperService,
        preTapFrame: com.myra.assistant.screen.ScreenFrame
    ) {
        val target = resolvedTarget.targetText
        val position = resolvedTarget.position
        val ordinal = resolvedTarget.ordinal
        val foreground = accessibility.currentForegroundContext()
        val actionScope = com.myra.assistant.screen.ForegroundActionPolicy.scope(foreground)
        if (actionScope == null ||
            (preTapFrame.packageName != null && preTapFrame.packageName != actionScope.expectedPackage)
        ) {
            live?.sendToolResponse(id, "perform_screen_action", false, "The foreground app changed before target resolution")
            return
        }
        val before = accessibility.visibleScreenSignature()
        val actionSessionId = preTapFrame.sessionId
        val resolveStartedAt = android.os.SystemClock.elapsedRealtime()
        var createdIntent: ScreenActionIntent? = null
        val tapResult = accessibility.resolveAndTapVisibleTarget(
            target, position, ordinal, actionScope
        ) { _, confidence ->
            createdIntent = screenActionRegistry.create(
                activeTurnId, actionSessionId, lastUserIntentText,
                target, position, ordinal, accessibility.currentPackageName(),
                android.os.SystemClock.elapsedRealtime(), preTapFrame.frameId, confidence,
                actionScope.expectedWindowId, actionScope.expectedGeneration
            )
            true
        }
        val actionIntent = createdIntent ?: screenActionRegistry.create(
            activeTurnId, actionSessionId, lastUserIntentText,
            target, position, ordinal, accessibility.currentPackageName(),
            android.os.SystemClock.elapsedRealtime(), preTapFrame.frameId, tapResult.confidence,
            actionScope.expectedWindowId, actionScope.expectedGeneration
        )
        voiceLog(
            "SCREEN_ACTION_CREATED actionId=${actionIntent.actionId} turnId=${actionIntent.turnId} " +
                "screenSessionId=${actionIntent.screenSessionId} frameId=${actionIntent.sourceFrameId} " +
                "package=${actionIntent.appPackage} target=${actionIntent.normalizedTarget} confidence=${actionIntent.confidence} " +
                "resolverVersion=${actionIntent.resolverVersion}"
        )
        voiceLog(
            "SCREEN_TARGET_RESOLVED actionId=${actionIntent.actionId} resolution=${tapResult.resolution} " +
                "candidate=${tapResult.candidate?.label?.take(160)} confidence=${tapResult.confidence}"
        )
        val accepted = tapResult.accepted
        voiceLog(
            "SCREEN_ACTION_STARTED screen_query_id=${screenResponseQueryId.ifBlank { "tool-$activeTurnId" }} " +
                "screen_session_id=$actionSessionId frame_id=${preTapFrame.frameId} timestamp=$resolveStartedAt target=${target?.take(120)}"
        )
        voiceLog(
            "screen_action_target_resolved target=$target position=$position ordinal=$ordinal " +
                "screen_session_id=$actionSessionId preTapFrameId=${preTapFrame.frameId} " +
                "resolveAndTapMs=${(android.os.SystemClock.elapsedRealtime() - resolveStartedAt).coerceAtLeast(0L)}"
        )
        voiceLog("screen_action_tap_attempt target=$target accepted=$accepted screen_session_id=$actionSessionId")
        if (!accepted) {
            screenActionRegistry.cancel(actionIntent.actionId)
            brain.recordScreenAction(resolvedTarget, false)
            voiceLog("SCREEN_ACTION_FAILED actionId=${actionIntent.actionId} reason=${tapResult.resolution}")
            live?.sendToolResponse(id, "perform_screen_action", false, "No unambiguous clickable target matched the current screen")
            return
        }
        mainHandler.postDelayed({
            val query = ScreenCaptureService.requestFreshFrame(activeTurnId) { result ->
                mainHandler.post {
                    if (!screenActionRegistry.isExecutable(
                        actionIntent.actionId, actionIntent.turnId, actionIntent.screenSessionId,
                        accessibility.currentForegroundContext()
                    )) {
                        voiceLog("SCREEN_TARGET_STALE actionId=${actionIntent.actionId} turnId=${actionIntent.turnId} reason=replaced_before_verification")
                        return@post
                    }
                    val post = (result as? FreshFrameResult.Ready)?.frame
                    val accessibilityChanged = before.isNotBlank() && accessibility.visibleScreenSignature() != before
                    val frameChanged = post != null && post.sessionId == actionSessionId &&
                        post.frameId > preTapFrame.frameId && post.hash != preTapFrame.hash
                    val foregroundStillOwned =
                        com.myra.assistant.screen.ForegroundActionPolicy.canExecute(
                            actionScope, accessibility.currentForegroundContext()
                        )
                    val verified = ScreenCaptureService.session.isCurrent(actionSessionId) &&
                        foregroundStillOwned && (accessibilityChanged || frameChanged)
                    voiceLog(
                        "screen_action_post_frame screen_session_id=$actionSessionId preTapFrameId=${preTapFrame.frameId} " +
                            "postTapFrameId=${post?.frameId ?: 0L} frameChanged=$frameChanged accessibilityChanged=$accessibilityChanged"
                    )
                    voiceLog("screen_action_verification_result target=$target tapAccepted=true verified=$verified")
                    voiceLog(
                        "SCREEN_ACTION_VERIFIED actionId=${actionIntent.actionId} screen_session_id=$actionSessionId pre_frame_id=${preTapFrame.frameId} " +
                            "post_frame_id=${post?.frameId ?: 0L} timestamp=${android.os.SystemClock.elapsedRealtime()} verified=$verified"
                    )
                    brain.recordScreenAction(resolvedTarget, verified)
                    screenActionRegistry.cancel(actionIntent.actionId)
                    live?.sendToolResponse(
                        id, "perform_screen_action", verified,
                        if (verified) "The target was tapped and a new screen state was verified"
                        else "The target was tapped but the expected screen change was not verified"
                    )
                }
            }
            if (query == null) live?.sendToolResponse(id, "perform_screen_action", false, "Screen session ended before post-tap verification")
        }, 350L)
    }

    private fun beginFreshScreenQuery(question: String, userTurnId: Long) {
        screenQuestionDetectedAt = android.os.SystemClock.elapsedRealtime()
        val speechTiming = ScreenQueryTimingPolicy.bind(userTurnId, speechTimingTurnId, speechActivityEndedAt)
        screenQuerySpeechTurnConsistency = speechTiming.consistent
        screenResponseSpeechEndedAt = speechTiming.speechEndAt
        voiceLog(
            "screen_query_timing_bound userTurnId=$userTurnId speechTimingTurnId=$speechTimingTurnId " +
                "speechStartAt=$speechActivityStartedAt speechEndAt=$screenResponseSpeechEndedAt " +
                "intentDetectedAt=$screenQuestionDetectedAt screenQuerySpeechTurnConsistency=$screenQuerySpeechTurnConsistency"
        )
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        output.clear()
        // Preserve an active media-speech candidate when LYRA is already silent;
        // interrupt/reset is only needed for a genuine barge-in on LYRA playback.
        if (localAudioSpeaking) audio?.interrupt()
        if (!screenVisionPreferences.visionEnabled || ScreenCaptureService.currentState != ScreenShareState.ACTIVE) {
            voiceLog("screen_query_terminal state=REJECTED_SCREEN_INACTIVE userTurnId=$userTurnId")
            speakScreenUnavailable(
                if (ScreenCaptureService.currentState == ScreenShareState.PAUSED) "Screen Vision paused hai. Floating control se resume karo."
                else "Screen Vision abhi active nahi hai."
            )
            return
        }
        if (tryInstantScreenAnswer(question, userTurnId)) return
        val query = ScreenCaptureService.requestFreshFrame(userTurnId) { result ->
            mainHandler.post {
                when (result) {
                    is FreshFrameResult.Unavailable -> {
                        voiceLog("screen_frame_unavailable reason=${result.reason} screen_query_id=${result.query.queryId} screen_session_id=${result.query.sessionId}")
                        voiceLog("screen_query_terminal screenQueryId=${result.query.queryId} state=CAPTURE_FAILED reason=${result.reason}")
                        speakScreenUnavailable("Fresh screen frame nahi mili. Ek baar phir try karo.")
                    }
                    is FreshFrameResult.Ready -> {
                        val frame = result.frame
                        if (!ScreenCaptureService.session.isCurrent(result.query.sessionId)) {
                            voiceLog("screen_query_result_dropped_stale screen_query_id=${result.query.queryId} screen_session_id=${result.query.sessionId} reason=session_invalid_before_send")
                            return@post
                        }
                        voiceLog("screen_query_state screenQueryId=${result.query.queryId} state=FRAME_SELECTED frameId=${frame.frameId}")
                        val accessibility = AccessibilityHelperService.instance
                        val elements = accessibility?.visibleElements(100).orEmpty()
                        val privacyResult = ScreenFramePrivacyFilter.apply(
                            jpeg = frame.bytes,
                            elements = elements,
                            screenWidth = resources.displayMetrics.widthPixels,
                            screenHeight = resources.displayMetrics.heightPixels,
                            enabled = screenVisionPreferences.sensitiveContentProtection
                        )
                        if (privacyResult is ScreenPrivacyResult.Blocked) {
                            voiceLog(
                                "screen_privacy_filter screenQueryId=${result.query.queryId} frameId=${frame.frameId} " +
                                    "sensitiveProtectionEnabled=true sensitiveScanResult=SENSITIVE sensitiveCategoryDetected=${privacyResult.categories} " +
                                    "sensitiveRegionCount=0 redactionApplied=false fullFrameBlocked=true blockReason=${privacyResult.reason} safePixelsPreserved=false"
                            )
                            voiceLog("screen_query_terminal screenQueryId=${result.query.queryId} state=REJECTED_PRIVACY")
                            speakScreenPrivacyBlocked()
                            return@post
                        }
                        val allowed = privacyResult as ScreenPrivacyResult.Allowed
                        voiceLog(
                            "screen_privacy_filter screenQueryId=${result.query.queryId} frameId=${frame.frameId} " +
                                "sensitiveProtectionEnabled=${screenVisionPreferences.sensitiveContentProtection} " +
                                "sensitiveScanResult=${if (allowed.regionCount > 0) "REDACTED" else "SAFE"} " +
                                "sensitiveCategoryDetected=${allowed.categories} sensitiveRegionCount=${allowed.regionCount} " +
                                "redactionApplied=${allowed.redactionApplied} fullFrameBlocked=false blockReason=none safePixelsPreserved=true"
                        )
                        screenResponseActive = true
                        screenResponseHasContent = false
                        screenResponseStartedLogged = false
                        screenResponseGenerationComplete = false
                        screenResponseTextCommitted = false
                        screenResponseUserTurnId = result.query.userTurnId
                        screenResponseAfterGenerationId = latestObservedModelGenerationId
                        screenResponseGenerationId = 0L
                        screenResponseBinding = ScreenResponseBinding(
                            result.query.userTurnId, result.query.queryId, result.query.sessionId,
                            latestObservedModelGenerationId
                        )
                        screenResponseSessionId = result.query.sessionId
                        screenResponseQueryId = result.query.queryId
                        screenFreshFrameCapturedAt = frame.capturedAt
                        val now = android.os.SystemClock.elapsedRealtime()
                        val ui = elements.filter { ScreenPrivacyPolicy.sensitiveCategory(it.label) == null }.joinToString("\n") {
                            "${it.label} [${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}]${if (it.clickable) " clickable" else ""}"
                        }.take(12_000)
                        screenFrameSentAt = android.os.SystemClock.elapsedRealtime()
                        voiceLog(
                            "frame_used_for_query screen_query_id=${result.query.queryId} userTurnId=${result.query.userTurnId} " +
                                "screen_session_id=${frame.sessionId} frame_id=${frame.frameId} frame_age_ms=${now - frame.capturedAt} " +
                                "frame_hash=${frame.hash} speechEndAt=$screenResponseSpeechEndedAt screenQuestionDetectedAt=$screenQuestionDetectedAt " +
                                "freshCaptureRequestedAt=${result.query.requestedAt} freshFrameCapturedAt=${frame.capturedAt} frameEncodedAt=${frame.encodedAt} " +
                                "frameSource=${frame.source} frameAgeAtQueryMs=${(now - frame.capturedAt).coerceAtLeast(0L)} " +
                                "intentToFrameMs=${(now - screenQuestionDetectedAt).coerceAtLeast(0L)} captureToEncodeMs=${frame.encodedAt - frame.capturedAt} " +
                                "frameToGeminiSendMs=${(screenFrameSentAt - frame.encodedAt).coerceAtLeast(0L)} frameSentToGeminiAt=$screenFrameSentAt " +
                                "screenQuerySpeechTurnConsistency=$screenQuerySpeechTurnConsistency " +
                                "speechEndToIntentMs=${if (screenQuerySpeechTurnConsistency) (screenQuestionDetectedAt - screenResponseSpeechEndedAt).coerceAtLeast(0L) else -1L}"
                        )
                        voiceLog("screen_query_state screenQueryId=${result.query.queryId} state=SENT frameId=${frame.frameId}")
                        voiceLog(
                            "VISION_REQUEST_STARTED screenQueryId=${result.query.queryId} screen_session_id=${result.query.sessionId} " +
                                "frame_id=${frame.frameId} timestamp=$screenFrameSentAt frameWaitMs=${(now - result.query.requestedAt).coerceAtLeast(0L)}"
                        )
                        live?.sendImage(
                            allowed.bytes, "image/jpeg",
                            "$question\nDescribe only the newest supplied screen frame for query ${result.query.queryId}. " +
                                "Do not answer from older visual context. If text is readable, summarize only the visible page; never invent hidden or offscreen content. " +
                                "Screen sharing is ACTIVE. Current safe accessibility elements:\n$ui\n" +
                                "If uncertain, say exactly what is uncertain. Keep the spoken answer to one or two complete sentences."
                        )
                        mainHandler.postDelayed({
                            if (screenResponseActive && screenResponseQueryId == result.query.queryId && !screenResponseHasContent) {
                                voiceLog("screen_query_orphaned screenQueryId=${result.query.queryId} lastState=SENT userTurnId=${result.query.userTurnId}")
                            }
                        }, SCREEN_QUERY_DIAGNOSTIC_TIMEOUT_MS)
                    }
                }
            }
        }
        if (query == null) speakScreenUnavailable("Screen Vision initialize ho raha hai. Ek baar phir try karo.")
        else voiceLog("screen_query_created screen_query_id=${query.queryId} screen_session_id=${query.sessionId} userTurnId=$userTurnId state=CREATED")
    }

    private fun tryInstantScreenAnswer(question: String, userTurnId: Long): Boolean {
        val queryType = ScreenVisionIntentParser.parseInstantQuery(question) ?: return false
        val now = android.os.SystemClock.elapsedRealtime()
        val context = ScreenContextStore.freshSnapshot(
            ScreenCaptureService.session.sessionId, now, ScreenCacheUse.QUESTION
        ) ?: run {
            voiceLog("FRAME_STALE userTurnId=$userTurnId route=HOT_SCREEN_CACHE fallback=VISION")
            return false
        }
        val safeText = context.summary.visibleText.filter {
            ScreenPrivacyPolicy.sensitiveCategory(it) == null
        }
        val app = context.summary.appName ?: context.summary.packageName?.substringAfterLast('.')
        val answer = when (queryType) {
            InstantScreenQuery.CURRENT_APP -> app?.let { "$it open hai." }
            InstantScreenQuery.OVERVIEW -> {
                val useful = safeText.filter { it.length >= 3 }.distinct().take(3)
                when {
                    useful.isNotEmpty() && app != null -> "$app open hai. Screen par ${useful.joinToString(", ")} dikh raha hai."
                    useful.isNotEmpty() -> "Screen par ${useful.joinToString(", ")} dikh raha hai."
                    app != null -> "$app open hai, lekin readable text clear nahi hai."
                    else -> null
                }
            }
        } ?: return false
        val newestAt = maxOf(context.frameTimestamp, context.accessibilityTimestamp)
        instantScreenQueryId = "hot-$userTurnId-${now.toString(16)}"
        instantScreenQueryStartedAt = screenResponseSpeechEndedAt.takeIf {
            screenQuerySpeechTurnConsistency && it > 0L
        } ?: now
        instantScreenCacheAgeMs = (now - newestAt).coerceAtLeast(0L)
        voiceLog(
            "FRAME_SELECTED screenQueryId=$instantScreenQueryId userTurnId=$userTurnId source=HOT_SCREEN_CACHE " +
                "screen_session_id=${context.screenSessionId} frame_id=${context.frameId} frame_age_ms=$instantScreenCacheAgeMs"
        )
        voiceLog(
            "TOTAL_SCREEN_RESPONSE screenQueryId=$instantScreenQueryId route=HOT_SCREEN_CACHE stage=ANSWER_READY " +
                "voice_ms=-1 capture_ms=0 accessibility_ms=0 vision_ms=0 gemini_ms=0 tts_ms=-1 " +
                "total_ms=${(now - instantScreenQueryStartedAt).coerceAtLeast(0L)}"
        )
        emitState(answer)
        queueLocalSpeech(answer, allowUntranscribedAudio = true)
        return true
    }

    private fun armScreenQuestion(question: String, userTurnId: Long, source: String) {
        if (question.isBlank() || userTurnId == 0L) return
        if (armedScreenQuestionTurnId == userTurnId && armedScreenQuestion.isNotBlank()) return
        armedScreenQuestion = question
        armedScreenQuestionTurnId = userTurnId
        armedScreenQuestionDetectedAt = android.os.SystemClock.elapsedRealtime()
        voiceLog(
            "screen_query_intent_detected_at=$armedScreenQuestionDetectedAt userTurnId=$userTurnId source=$source " +
                "speechEndAt=$speechActivityEndedAt finalTranscriptAt=0 stableFinalBubbleCommitted=false"
        )
        // ASR chunks and local VAD are independent streams. If the decisive words land
        // just after VAD ended, dispatch now instead of waiting for a second VAD edge.
        if (speechActivityEndedAt >= inputTurnStartedAt && speechActivityEndedAt > 0L) {
            mainHandler.post { dispatchArmedScreenQuestionAtSpeechEnd() }
        }
    }

    private fun dispatchArmedScreenQuestionAtSpeechEnd() {
        val question = armedScreenQuestion.takeIf { it.isNotBlank() } ?: return
        val turnId = armedScreenQuestionTurnId.takeIf { it != 0L } ?: return
        if (screenResponseActive) return
        val now = android.os.SystemClock.elapsedRealtime()
        voiceLog(
            "screen_query_early_dispatch userTurnId=$turnId speech_end_at=$speechActivityEndedAt " +
                "screen_query_intent_detected_at=$armedScreenQuestionDetectedAt speechEndToIntentMs=${(armedScreenQuestionDetectedAt - speechActivityEndedAt).coerceAtLeast(0L)} " +
                "intentToDispatchMs=${(now - armedScreenQuestionDetectedAt).coerceAtLeast(0L)}"
        )
        earlyScreenQueryAwaitingFinalTranscript = true
        earlyScreenQueryDispatchedTurnId = turnId
        beginFreshScreenQuery(question, turnId)
        armedScreenQuestion = ""
    }

    private fun speakScreenUnavailable(message: String) {
        screenResponseActive = false
        screenResponseHasContent = false
        screenResponseStartedLogged = false
        screenResponseGenerationComplete = false
        screenResponseTextCommitted = false
        screenResponseUserTurnId = 0L
        screenResponseAfterGenerationId = 0L
        screenResponseGenerationId = 0L
        screenResponseBinding = null
        screenResponseSessionId = ""
        screenResponseQueryId = ""
        listener?.onMyraText(message, true)
        emitState(message)
        queueLocalSpeech(message, allowUntranscribedAudio = true)
    }

    private fun speakScreenPrivacyBlocked() {
        val message = "Sensitive information visible hai, isliye main screen details read nahi kar rahi."
        listener?.onMyraText(message, true)
        emitState(message)
        queueLocalSpeech(message, allowUntranscribedAudio = true)
    }

    private fun finishScreenResponse(reason: String) {
        voiceLog("screen_query_terminal screenQueryId=$screenResponseQueryId state=${if (reason == "real_user_barge_in") "CANCELLED_REAL_BARGE_IN" else "COMPLETED"} reason=$reason")
        voiceLog("screen_response_playback_end screen_query_id=$screenResponseQueryId screen_session_id=$screenResponseSessionId reason=$reason")
        screenResponseActive = false
        screenResponseHasContent = false
        screenResponseStartedLogged = false
        screenResponseGenerationComplete = false
        screenResponseTextCommitted = false
        screenResponseUserTurnId = 0L
        screenResponseAfterGenerationId = 0L
        screenResponseGenerationId = 0L
        screenResponseBinding = null
        screenResponseSessionId = ""
        screenResponseQueryId = ""
        resetTurnBuffers("screen_response_$reason")
    }

    private fun handleScreenMemoryProposal(id: String, args: org.json.JSONObject) {
        val prefs = screenVisionPreferences
        if (!prefs.visionEnabled || !prefs.automaticLearning || !prefs.saveScreenMemories ||
            !ScreenCaptureService.hasFreshFrame()
        ) {
            live?.sendToolResponse(id, "propose_screen_memory", false, "Automatic screen memory is disabled")
            return
        }
        val fact = args.optString("fact").trim().replace(Regex("\\s+"), " ")
        val categoryName = args.optString("category").uppercase(Locale.ROOT)
        val confidence = args.optDouble("confidence", 0.0)
        val stableKey = args.optString("memory_key").lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_:]+"), "_").trim('_').take(80)
        if (fact.length !in 5..200 || stableKey.isBlank() ||
            !ScreenPrivacyPolicy.isMemoryWorthy(categoryName, confidence) ||
            (prefs.sensitiveContentProtection && ScreenPrivacyPolicy.blocksLongTermMemory(fact))
        ) {
            live?.sendToolResponse(id, "propose_screen_memory", false, "Screen observation was not safe and durable enough to save")
            return
        }
        val category = runCatching { MemoryCategory.valueOf(categoryName) }.getOrNull()
        if (category == null) {
            live?.sendToolResponse(id, "propose_screen_memory", false, "Unsupported memory category")
            return
        }
        serviceScope.launch {
            val candidate = MemoryCandidate(
                category, fact, "screen:$stableKey", MemorySensitivity.LOW,
                confidence, source = "screen_observation"
            )
            val result = if (memoryRepository.isAlreadySaved(candidate)) {
                MemoryWriteResult.Saved("existing")
            } else memoryRepository.save(candidate)
            val saved = result is MemoryWriteResult.Saved
            voiceLog("screen_memory_write fact=${fact.take(80)} source=screen_observation saved=$saved")
            live?.sendToolResponse(
                id, "propose_screen_memory", saved,
                if (saved) "Structured screen observation saved in the existing Memory Brain"
                else "Screen observation was not saved"
            )
        }
    }

    private fun handleSemanticMemoryProposal(id: String, args: org.json.JSONObject) {
        val guardedText = lastUserIntentText.ifBlank { input.toString().trim() }
        if (guardedText.isBlank() || MemoryCommandParser.looksLikeIntent(romanDisplayText(guardedText))) {
            live?.sendToolResponse(id, "propose_user_memory", false, "Explicit memory commands are handled locally")
            return
        }
        if (pendingPersonalMemory != null || pendingDetectedPersonalMemory != null ||
            PersonalMemoryExtractor.extract(romanDisplayText(guardedText)) != null ||
            PersonLinkedMemoryExtractor.extractAll(romanDisplayText(guardedText)).isNotEmpty() ||
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
            when {
                MemoryRelationshipPolicy.isBestFriend(candidate) -> {
                    memoryRepository.saveAdditionalBestFriend(candidate)
                    live?.sendToolResponse(id, "propose_user_memory", true, "Saved silently; continue the conversation naturally without mentioning memory")
                }
                MemorySafetyPolicy.decide(candidate) == MemorySaveDecision.REJECT ->
                    live?.sendToolResponse(id, "propose_user_memory", false, "Android rejected this memory")
                MemorySafetyPolicy.decide(candidate) == MemorySaveDecision.AUTO_SAVE -> {
                    memoryRepository.save(candidate)
                    live?.sendToolResponse(id, "propose_user_memory", true, "Saved silently; continue the conversation naturally without mentioning memory")
                }
                else -> mainHandler.post {
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
        commitFinalUserMessage(raw.trim(), "PHONE_ACTION_CONFIRMATION")
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
        brain.recordPhoneAction(
            app = (command as? AppCommand.OpenApp)?.appName,
            action = command.toString(),
            success = result.success && result.verified
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

    private fun handleBrainCancellation(taskToken: Long) {
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        waitingForFreshInputAfterCommand = true
        pendingActionAfterLocalSpeech = null
        pendingConfirmedCommand = null
        pendingConfirmationExpiresAt = 0L
        output.clear(); commandProbe.clear()
        cancelSpeechForNewAction()
        screenActionRegistry.cancel()?.let {
            voiceLog("SCREEN_ACTION_CANCELLED actionId=${it.actionId} turnId=${it.turnId} reason=user_cancelled")
        }
        brain.finishTask(taskToken, true)
        val message = "Theek hai, rok diya."
        listener?.onMyraText(message)
        emitState(message)
        queueLocalSpeech(message, allowUntranscribedAudio = true)
        voiceLog("brain_task_cancelled taskToken=$taskToken")
    }

    private fun handleReadingCommand(command: ReadingCommand, turnId: Long): Boolean {
        val current = readingTracker.snapshot()
        if (command !is ReadingCommand.Start && current == null) return false
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        waitingForFreshInputAfterCommand = true
        when (command) {
            ReadingCommand.Start -> {
                if (!screenCommandTurnGuard.tryCommit(turnId)) {
                    voiceLog("screen_command_duplicate_dropped turnId=$turnId source=reading_start")
                    return true
                }
                startArticleReading(turnId)
            }
            ReadingCommand.Stop -> stopArticleReading("user_stop", "Theek hai, reading rok di.")
            ReadingCommand.Pause -> {
                readingTracker.pause()
                pendingActionAfterLocalSpeech = null
                audio?.interrupt(); live?.interrupt()
                speakReadingStatus("Reading pause kar di.")
                voiceLog("READING_SESSION_PAUSED reading_session_id=${current?.readingSessionId} timestamp=${android.os.SystemClock.elapsedRealtime()}")
            }
            ReadingCommand.Resume, ReadingCommand.Continue -> {
                if (current?.screenSessionId != ScreenCaptureService.session.sessionId ||
                    ScreenCaptureService.currentState != ScreenShareState.ACTIVE
                ) {
                    stopArticleReading("screen_session_changed", "Screen sharing active nahi hai.")
                } else {
                    readingTracker.resume()
                    readCurrentArticleContent(turnId, allowAutoScroll = true)
                }
            }
            ReadingCommand.StartAgain -> {
                readingTracker.resetProgress()
                val accessibility = AccessibilityHelperService.instance
                val active = readingTracker.snapshot()
                val accepted = if (accessibility != null && active?.scrollContainerId != null) {
                    accessibility.scrollArticleToBeginning(
                        active.scrollContainerId, active.foregroundPackage, active.screenSessionId
                    ) { _ -> mainHandler.post { readCurrentArticleContent(turnId, allowAutoScroll = true) } }
                } else false
                if (!accepted) readCurrentArticleContent(turnId, allowAutoScroll = true)
            }
            ReadingCommand.ReadAgain -> readCurrentArticleContent(turnId, allowAutoScroll = false, forceRepeat = true)
            ReadingCommand.ReadNewOnly -> {
                readingTracker.resume()
                readCurrentArticleContent(turnId, allowAutoScroll = false)
            }
            ReadingCommand.Forget -> {
                pendingActionAfterLocalSpeech = null
                readingTracker.forget()
                speakReadingStatus("Reading position bhool gayi.")
            }
        }
        return true
    }

    private fun startArticleReading(turnId: Long) {
        if (!screenVisionPreferences.visionEnabled || ScreenCaptureService.currentState != ScreenShareState.ACTIVE) {
            speakReadingStatus("Screen sharing is off.", error = true)
            return
        }
        val accessibility = AccessibilityHelperService.instance
        if (accessibility == null || !AccessibilityHelperService.isEnabled(this)) {
            speakReadingStatus("Article reading ke liye LYRA Accessibility enable karo.", error = true)
            return
        }
        val contentType = accessibility.detectContentType()
        if (contentType != ScreenContentType.ARTICLE) {
            val message = when (contentType) {
                ScreenContentType.VIDEO_PLATFORM -> "Ye YouTube hai, article nahi. Auto-scroll start nahi karungi."
                ScreenContentType.SOCIAL_FEED -> "Ye social feed hai, article nahi. Auto-scroll start nahi karungi."
                else -> "Current page ko article ke roop mein safely confirm nahi kar pa rahi. Auto-scroll start nahi karungi."
            }
            voiceLog("READING_START_REJECTED contentType=$contentType turnId=$turnId")
            speakReadingStatus(message, error = true)
            return
        }
        val context = com.myra.assistant.screen.ScreenContextStore.snapshot()
        val identity = listOfNotNull(context.currentPackage, accessibility.visibleArticleText().firstOrNull())
            .joinToString(":").take(300)
        val containerId = accessibility.currentArticleScrollContainerId() ?: run {
            voiceLog("READING_START_REJECTED contentType=$contentType turnId=$turnId reason=no_article_scroll_container")
            speakReadingStatus("Article ka safe scroll area nahi mila. Auto-scroll start nahi karungi.", error = true)
            return
        }
        val session = readingTracker.start(
            ScreenCaptureService.session.sessionId, identity,
            accessibility.currentPackageName().orEmpty(), contentType, explicitlyRequested = true,
            scrollContainerId = containerId
        ) ?: run {
            speakReadingStatus("Article reading start nahi hui.", error = true)
            return
        }
        voiceLog(
            "READING_SESSION_STARTED reading_session_id=${session.readingSessionId} " +
                "screen_session_id=${session.screenSessionId} container_id=${session.scrollContainerId} " +
                "timestamp=${android.os.SystemClock.elapsedRealtime()} contentType=$contentType"
        )
        readCurrentArticleContent(turnId, allowAutoScroll = true)
    }

    private fun readCurrentArticleContent(
        turnId: Long,
        allowAutoScroll: Boolean,
        forceRepeat: Boolean = false
    ) {
        val session = readingTracker.snapshot() ?: return
        val foregroundPackage = accessibilityPackage()
        if (readingTracker.pauseIfContextChanged(ScreenCaptureService.session.sessionId, foregroundPackage)) {
            pendingActionAfterLocalSpeech = null
            voiceLog("ARTICLE_SCROLL_REJECTED reading_session_id=${session.readingSessionId} reason=context_changed package=$foregroundPackage")
            return
        }
        if (session.state !in setOf(ReadingState.READING, ReadingState.VERIFYING_NEW_CONTENT) ||
            !ScreenCaptureService.session.isCurrent(session.screenSessionId)
        ) return
        val accessibility = AccessibilityHelperService.instance ?: return
        if (accessibility.detectContentType() != ScreenContentType.ARTICLE) {
            stopArticleReading("article_boundary", "Article complete.")
            return
        }
        val frameLookupAt = android.os.SystemClock.elapsedRealtime()
        val query = ScreenCaptureService.requestFreshFrame(turnId) { result ->
            mainHandler.post {
                val frame = (result as? FreshFrameResult.Ready)?.frame
                if (frame == null || !ScreenCaptureService.session.isCurrent(session.screenSessionId)) {
                    stopArticleReading("fresh_frame_unavailable", "Fresh article screen nahi mili.", error = true)
                    return@post
                }
                accessibility.refreshScreenContext()
                readingTracker.recordObservation(frame.frameId, accessibility.lastSnapshotAt())
                val lines = accessibility.visibleArticleText()
                val fresh = if (forceRepeat) {
                    lines.map { com.myra.assistant.screen.ReadingSegment(it, ReadingTracker.fingerprint(it)) }
                } else readingTracker.acceptVisibleText(lines, android.os.SystemClock.elapsedRealtime())
                val currentSession = readingTracker.snapshot() ?: return@post
                voiceLog(
                    "READING_NEW_CONTENT_FOUND reading_session_id=${currentSession.readingSessionId} frame_id=${frame.frameId} " +
                        "timestamp=${android.os.SystemClock.elapsedRealtime()} newSegments=${fresh.size} frameWaitMs=${android.os.SystemClock.elapsedRealtime() - frameLookupAt}"
                )
                if (fresh.isEmpty()) {
                    voiceLog("READING_DUPLICATE_SKIPPED reading_session_id=${currentSession.readingSessionId} frame_id=${frame.frameId} visibleSegments=${lines.size}")
                    if (allowAutoScroll && readingTracker.canAutoScroll()) autoScrollArticle(turnId)
                    else finishArticleAtEnd()
                    return@post
                }
                val spoken = fresh.joinToString(" ") { it.text }.take(MAX_READING_CHARS_PER_SCREEN)
                voiceLog(
                    "READING_CONTENT_READ reading_session_id=${currentSession.readingSessionId} frame_id=${frame.frameId} " +
                        "timestamp=${android.os.SystemClock.elapsedRealtime()} chars=${spoken.length} segments=${fresh.size}"
                )
                listener?.onMyraText(spoken)
                emitState("Article padh rahi hoon…")
                if (allowAutoScroll) readingTracker.markWaitingForScroll()
                pendingActionAfterLocalSpeech = if (allowAutoScroll) ({ autoScrollArticle(turnId) }) else null
                queueLocalSpeech(spoken, allowUntranscribedAudio = false)
            }
        }
        if (query == null) stopArticleReading("screen_inactive", "Screen sharing is off.", error = true)
    }

    private fun autoScrollArticle(turnId: Long) {
        val session = readingTracker.snapshot() ?: return
        val foregroundPackage = accessibilityPackage()
        if (readingTracker.pauseIfContextChanged(ScreenCaptureService.session.sessionId, foregroundPackage) ||
            foregroundPackage == "com.google.android.youtube"
        ) {
            pendingActionAfterLocalSpeech = null
            voiceLog("ARTICLE_SCROLL_REJECTED reading_session_id=${session.readingSessionId} reason=package_changed package=$foregroundPackage")
            return
        }
        val accessibility = AccessibilityHelperService.instance ?: run { finishArticleAtEnd(); return }
        val containerId = session.scrollContainerId ?: run {
            voiceLog("ARTICLE_SCROLL_REJECTED reading_session_id=${session.readingSessionId} reason=unbound_container")
            finishArticleAtEnd()
            return
        }
        if (!readingTracker.shouldAutoScroll(containerId, session.screenSessionId, foregroundPackage) ||
            !readingTracker.recordAutoScroll()
        ) {
            finishArticleAtEnd()
            return
        }
        voiceLog(
            "READING_AUTO_SCROLL_STARTED reading_session_id=${session.readingSessionId} " +
                "timestamp=${android.os.SystemClock.elapsedRealtime()} count=${readingTracker.snapshot()?.consecutiveAutoScrollCount}"
        )
        val accepted = accessibility.scrollArticleVerified(
            containerId, session.foregroundPackage, session.screenSessionId
        ) { changed ->
            mainHandler.post {
                val active = readingTracker.snapshot()
                if (active?.state != ReadingState.SCROLLING) return@post
                voiceLog(
                    "READING_AUTO_SCROLL_COMPLETED reading_session_id=${active.readingSessionId} " +
                        "timestamp=${android.os.SystemClock.elapsedRealtime()} changed=$changed"
                )
                if (!changed) finishArticleAtEnd()
                else {
                    ScreenCaptureService.requestFreshFrame(turnId) { fresh ->
                        mainHandler.post {
                            val frame = (fresh as? FreshFrameResult.Ready)?.frame
                            val current = readingTracker.snapshot() ?: return@post
                            if (frame == null || frame.sessionId != current.screenSessionId ||
                                frame.frameId <= current.lastFrameId
                            ) {
                                voiceLog("ARTICLE_SCROLL_REJECTED reading_session_id=${current.readingSessionId} reason=no_fresh_post_scroll_frame")
                                finishArticleAtEnd()
                                return@post
                            }
                            readingTracker.markVerifyingNewContent(frame.frameId, accessibility.lastSnapshotAt())
                            voiceLog("ARTICLE_SCROLL_VERIFIED reading_session_id=${current.readingSessionId} preFrameId=${current.lastFrameId} postFrameId=${frame.frameId}")
                            readCurrentArticleContent(turnId, allowAutoScroll = true)
                        }
                    }
                }
            }
        }
        if (!accepted) finishArticleAtEnd()
    }

    private fun finishArticleAtEnd() {
        val id = readingTracker.snapshot()?.readingSessionId
        readingTracker.complete()
        pendingActionAfterLocalSpeech = null
        voiceLog("READING_END_DETECTED reading_session_id=$id timestamp=${android.os.SystemClock.elapsedRealtime()}")
        speakReadingStatus("Article complete.")
    }

    private fun stopArticleReading(reason: String, message: String, error: Boolean = false) {
        val id = readingTracker.snapshot()?.readingSessionId
        readingTracker.stop()
        pendingActionAfterLocalSpeech = null
        audio?.interrupt(); live?.interrupt()
        voiceLog("READING_SESSION_STOPPED reading_session_id=$id timestamp=${android.os.SystemClock.elapsedRealtime()} reason=$reason")
        speakReadingStatus(message, error)
    }

    private fun speakReadingStatus(message: String, error: Boolean = false) {
        listener?.onMyraText(message, error)
        emitState(message)
        queueLocalSpeech(message, allowUntranscribedAudio = true)
    }

    private fun accessibilityPackage(): String =
        AccessibilityHelperService.instance?.currentPackageName().orEmpty()

    private fun executeBrainMultiStep(plan: BrainDecision.ScrollThenOpenVideo) {
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        waitingForFreshInputAfterCommand = true
        cancelSpeechForNewAction()
        val accessibility = AccessibilityHelperService.instance
        if (!screenVisionPreferences.visionEnabled || ScreenCaptureService.currentState != ScreenShareState.ACTIVE) {
            finishBrainTask(plan.taskToken, false, "Screen Vision active nahi hai.")
            return
        }
        if (accessibility == null || !AccessibilityHelperService.isEnabled(this)) {
            finishBrainTask(plan.taskToken, false, "LYRA Accessibility enable karo.")
            return
        }
        val down = plan.direction == BrainScrollDirection.DOWN
        voiceLog("brain_plan_started taskToken=${plan.taskToken} plan=scroll_then_open ordinal=${plan.ordinal} direction=${plan.direction}")
        val accepted = accessibility.scrollYouTubeVerified(down) { scrolled ->
            mainHandler.post {
                if (!brain.isTaskCurrent(plan.taskToken)) return@post
                if (!scrolled) {
                    finishBrainTask(plan.taskToken, false, "Screen scroll nahi hua.")
                    return@post
                }
                ScreenCaptureService.requestFreshFrame(activeTurnId) { fresh ->
                    mainHandler.post {
                        if (!brain.isTaskCurrent(plan.taskToken)) return@post
                        val beforeFrame = (fresh as? FreshFrameResult.Ready)?.frame
                        if (beforeFrame == null || !ScreenCaptureService.session.isCurrent(beforeFrame.sessionId)) {
                            finishBrainTask(plan.taskToken, false, "Scroll ke baad fresh screen nahi mili.")
                            return@post
                        }
                        val beforeSignature = accessibility.visibleScreenSignature()
                        val actionIntent = screenActionRegistry.create(
                            activeTurnId, beforeFrame.sessionId,
                            lastUserIntentText, "video", null, plan.ordinal,
                            accessibility.currentPackageName(), android.os.SystemClock.elapsedRealtime(),
                            beforeFrame.frameId, 1.0
                        )
                        voiceLog("SCREEN_ACTION_CREATED actionId=${actionIntent.actionId} turnId=${actionIntent.turnId} screenSessionId=${actionIntent.screenSessionId} frameId=${actionIntent.sourceFrameId} resolverVersion=${actionIntent.resolverVersion}")
                        val tapped = accessibility.tapVisibleYouTubeVideo(plan.ordinal)
                        voiceLog("brain_plan_step taskToken=${plan.taskToken} step=tap ordinal=${plan.ordinal} accepted=$tapped")
                        if (!tapped) {
                            finishBrainTask(plan.taskToken, false, "Second video clear nahi mila.")
                            return@post
                        }
                        mainHandler.postDelayed({
                            ScreenCaptureService.requestFreshFrame(activeTurnId) { postResult ->
                                mainHandler.post {
                                    if (!brain.isTaskCurrent(plan.taskToken)) return@post
                                    if (!screenActionRegistry.isExecutable(
                        actionIntent.actionId, actionIntent.turnId, actionIntent.screenSessionId,
                        accessibility.currentForegroundContext()
                    )) {
                                        voiceLog("SCREEN_ACTION_CANCELLED actionId=${actionIntent.actionId} reason=replaced_before_verification")
                                        return@post
                                    }
                                    val postFrame = (postResult as? FreshFrameResult.Ready)?.frame
                                    val accessibilityChanged = beforeSignature.isNotBlank() &&
                                        accessibility.visibleScreenSignature() != beforeSignature
                                    val frameChanged = postFrame != null && postFrame.sessionId == beforeFrame.sessionId &&
                                        postFrame.frameId > beforeFrame.frameId && postFrame.hash != beforeFrame.hash
                                    val verified = ScreenCaptureService.session.isCurrent(beforeFrame.sessionId) &&
                                        (accessibilityChanged || frameChanged)
                                    brain.recordScreenAction(
                                        ScreenTargetReference(targetText = "video", ordinal = plan.ordinal),
                                        verified
                                    )
                                    screenActionRegistry.cancel(actionIntent.actionId)
                                    finishBrainTask(
                                        plan.taskToken,
                                        verified,
                                        if (verified) "Video open ho gaya."
                                        else "Tap hua, lekin video open hona verify nahi hua."
                                    )
                                }
                            }
                        }, 400L)
                    }
                }
            }
        }
        if (!accepted) finishBrainTask(plan.taskToken, false, "YouTube scroll start nahi hua.")
    }

    private fun executeContextualScreenAction(target: ScreenTargetReference) {
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        waitingForFreshInputAfterCommand = true
        cancelSpeechForNewAction()
        val accessibility = AccessibilityHelperService.instance
        if (!screenVisionPreferences.visionEnabled || ScreenCaptureService.currentState != ScreenShareState.ACTIVE) {
            finishBrainTask(brain.snapshot().taskToken, false, "Screen Vision active nahi hai.")
            return
        }
        if (accessibility == null || !AccessibilityHelperService.isEnabled(this)) {
            finishBrainTask(brain.snapshot().taskToken, false, "LYRA Accessibility enable karo.")
            return
        }
        val taskToken = brain.snapshot().taskToken
        val actionScope = com.myra.assistant.screen.ForegroundActionPolicy.scope(
            accessibility.currentForegroundContext()
        )
        if (actionScope == null) {
            finishBrainTask(taskToken, false, "Current app clear nahi mila.")
            return
        }
        val query = ScreenCaptureService.requestFreshFrame(activeTurnId) { freshResult ->
            mainHandler.post {
                if (!brain.isTaskCurrent(taskToken)) return@post
                val beforeFrame = (freshResult as? FreshFrameResult.Ready)?.frame
                if (beforeFrame == null || !ScreenCaptureService.session.isCurrent(beforeFrame.sessionId)) {
                    finishBrainTask(taskToken, false, "Fresh screen context nahi mila.")
                    return@post
                }
                val beforeSignature = accessibility.visibleScreenSignature()
                var actionIntent: ScreenActionIntent? = null
                if (beforeFrame.packageName != null &&
                    beforeFrame.packageName != actionScope.expectedPackage
                ) {
                    finishBrainTask(taskToken, false, "App change ho gaya; old target use nahi kiya.")
                    return@post
                }
                val tapResult = accessibility.resolveAndTapVisibleTarget(
                    target.targetText, target.position, target.ordinal, actionScope
                ) { _, confidence ->
                    actionIntent = screenActionRegistry.create(
                        activeTurnId, beforeFrame.sessionId, lastUserIntentText,
                        target.targetText, target.position, target.ordinal,
                        accessibility.currentPackageName(), android.os.SystemClock.elapsedRealtime(),
                        beforeFrame.frameId, confidence,
                        actionScope.expectedWindowId, actionScope.expectedGeneration
                    )
                    true
                }
                val ownedAction = actionIntent
                val accepted = tapResult.accepted && ownedAction != null
                if (!accepted) {
                    ownedAction?.let { screenActionRegistry.cancel(it.actionId) }
                    brain.recordScreenAction(target, false)
                    finishBrainTask(taskToken, false, "Doosra target clear nahi mila.")
                    return@post
                }
                mainHandler.postDelayed({
                    ScreenCaptureService.requestFreshFrame(activeTurnId) { result ->
                        mainHandler.post {
                            if (!brain.isTaskCurrent(taskToken)) return@post
                            val action = ownedAction ?: return@post
                            if (!screenActionRegistry.isExecutable(
                                action.actionId, action.turnId, action.screenSessionId,
                                accessibility.currentForegroundContext()
                            )) {
                                voiceLog("SCREEN_ACTION_CANCELLED actionId=${action.actionId} reason=replaced_before_verification")
                                return@post
                            }
                            val postFrame = (result as? FreshFrameResult.Ready)?.frame
                            val accessibilityChanged = beforeSignature.isNotBlank() &&
                                accessibility.visibleScreenSignature() != beforeSignature
                            val frameChanged = postFrame != null && postFrame.sessionId == beforeFrame.sessionId &&
                                postFrame.frameId > beforeFrame.frameId && postFrame.hash != beforeFrame.hash
                            val foregroundStillOwned =
                                com.myra.assistant.screen.ForegroundActionPolicy.canExecute(
                                    actionScope, accessibility.currentForegroundContext()
                                )
                            val verified = ScreenCaptureService.session.isCurrent(beforeFrame.sessionId) &&
                                foregroundStillOwned && (accessibilityChanged || frameChanged)
                            brain.recordScreenAction(target, verified)
                            screenActionRegistry.cancel(action.actionId)
                            finishBrainTask(
                                taskToken,
                                verified,
                                if (verified) "Doosra wala open ho gaya."
                                else "Tap hua, lekin screen change verify nahi hua."
                            )
                        }
                    }
                }, 400L)
            }
        }
        if (query == null) finishBrainTask(taskToken, false, "Screen Vision active nahi hai.")
    }

    private fun finishBrainTask(taskToken: Long, success: Boolean, message: String) {
        if (!brain.isTaskCurrent(taskToken)) return
        brain.finishTask(taskToken, success)
        listener?.onMyraText(message, !success)
        emitState(message)
        queueLocalSpeech(message, allowUntranscribedAudio = success)
        voiceLog("brain_task_finished taskToken=$taskToken success=$success message=${message.take(100)}")
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
            val error = "Scroll ke liye LYRA Accessibility enable karo."
            listener?.onMyraText(error, true)
            emitState(error)
            queueLocalSpeech(error)
            return
        }
        val explicitYouTube = command.explicitlyRequestedApp.equals("YouTube", true)
        val actionScope = com.myra.assistant.screen.ForegroundActionPolicy.scope(
            service.currentForegroundContext()
        )
        if (!explicitYouTube && actionScope == null) {
            val error = "Current app clear nahi mila, isliye scroll nahi kiya."
            listener?.onMyraText(error, true)
            emitState(error)
            queueLocalSpeech(error)
            return
        }
        val callback: (Boolean) -> Unit = { success ->
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
                    val error = "Current screen move nahi hua. Screen unlock rakho aur phir try karo."
                    listener?.onMyraText(error, true)
                    emitState(error)
                    queueLocalSpeech(error)
                }
            }
        }
        val accepted = if (explicitYouTube) {
            service.scrollYouTubeVerified(
                resolvedDirection == AppCommand.ScrollDirection.DOWN,
                callback
            )
        } else {
            service.scrollCurrentForegroundVerified(
                actionScope!!,
                resolvedDirection == AppCommand.ScrollDirection.DOWN,
                callback
            )
        }
        if (!accepted) {
            val error = if (explicitYouTube) {
                "YouTube is phone mein nahi mila."
            } else {
                "Current app mein safe scroll area nahi mila."
            }
            listener?.onMyraText(error, true)
            emitState(error)
            queueLocalSpeech(error)
        } else {
            emitState(if (explicitYouTube) "YouTube scroll kar rahi hoon…" else "Current screen scroll kar rahi hoon…")
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

    private fun beginOrdinarySpeechActivity(latestGenerationId: Long, source: String) {
        if (validatingLocalSpeech != null || !responseArbiter.acceptsOrdinaryModel()) return
        if (ordinaryModelAudioGate.isSpeechActive()) return
        speechActivityStartedAt = android.os.SystemClock.elapsedRealtime()
        speechActivityEndedAt = 0L
        speechTimingTurnId = activeTurnId
        val cancelledGeneration = ordinaryModelAudioGate.onSpeechActivityStarted(latestGenerationId)
        acceptedModelGenerationForTurn = 0L
        if (earlyModelAudio.isNotEmpty()) {
            modelAudioDroppedBeforeTurnCompleteCount += earlyModelAudio.size
            modelAudioDroppedBeforeTurnCompleteBytes += earlyModelAudioBytes
            earlyModelAudio.clear()
            earlyModelAudioBytes = 0L
            earlyModelAudioGenerationId = 0L
        }
        // If LYRA is silent, do not reset the media candidate that was just
        // confirmed from coherent ASR; real playback barge-in still interrupts.
        if (localAudioSpeaking) audio?.interrupt()
        voiceLog(
            "speech_activity_started turnId=$activeTurnId modelGenerationId=$latestGenerationId " +
                "speechActivityStartedAt=$speechActivityStartedAt source=$source " +
                "playbackCancelledByBargeIn=${cancelledGeneration != null} cancelledGenerationId=${cancelledGeneration ?: 0L}"
        )
    }

    private fun finishOrdinarySpeechActivity() {
        if (!ordinaryModelAudioGate.isSpeechActive()) return
        speechActivityEndedAt = android.os.SystemClock.elapsedRealtime()
        ordinaryModelAudioGate.onSpeechActivityEnded()
        voiceLog(
            "authoritative_user_turn_complete turnId=$activeTurnId modelGenerationId=$earlyModelAudioGenerationId " +
                "speechActivityEndedAt=$speechActivityEndedAt authoritativeUserTurnCompleteAt=$speechActivityEndedAt " +
                "speechTimingTurnId=$speechTimingTurnId speechDurationMs=${(speechActivityEndedAt - speechActivityStartedAt).coerceAtLeast(0L)} " +
                "source=local_vad userSpeechActive=false earlyModelAudioBufferedCount=${earlyModelAudio.size} " +
                "earlyModelAudioBufferedBytes=$earlyModelAudioBytes"
        )
        if (earlyModelAudio.isEmpty()) return
        val generationId = earlyModelAudioGenerationId
        val chunks = earlyModelAudio.toList()
        earlyModelAudio.clear()
        earlyModelAudioBytes = 0L
        earlyModelAudioGenerationId = 0L
        val decision = ordinaryModelAudioGate.decide(generationId)
        if (decision != ModelAudioDecision.ACCEPT) {
            modelAudioDroppedBeforeTurnCompleteCount += chunks.size
            modelAudioDroppedBeforeTurnCompleteBytes += chunks.sumOf { it.size.toLong() }
            voiceLog(
                "early_model_audio_dropped turnId=$activeTurnId modelGenerationId=$generationId " +
                    "rejectionReason=$decision staleAudioDropped=true chunks=${chunks.size}"
            )
            return
        }
        acceptedModelGenerationForTurn = generationId
        mediaGuard.beginAssistantTurn()
        audio?.setPlaybackContext(generationId, responseOwner = "MODEL")
        audio?.setBargeInEnabled(true)
        chunks.forEach { audio?.queueAudio(it) }
        val acceptedAt = android.os.SystemClock.elapsedRealtime()
        voiceLog(
            "early_model_audio_released turnId=$activeTurnId modelGenerationId=$generationId " +
                "firstModelAudioAcceptedAt=$acceptedAt firstPlaybackAt=$acceptedAt " +
                "userTurnCompleteToFirstPlaybackMs=${acceptedAt - speechActivityEndedAt} chunks=${chunks.size}"
        )
    }

    private fun cancelSpeechForNewAction() {
        // Clear validation/playback state before AudioEngine emits its interruption
        // callback. Otherwise finishLocalPlayback() can revive an expired model turn.
        localSpeechValidationToken++
        cancelLocalSpeechTimeout("speech_cancelled")
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
        val speechBusy = validatingLocalSpeech != null ||
            pendingLocalSpeech != null || localPlaybackActive || localAudioSpeaking
        if (LocalSpeechDuplicateGuard.shouldDrop(key == lastLocalSpeechKey, speechBusy)) {
            voiceLog("local_speech_dropped reason=duplicate ageMs=${now - lastLocalSpeechAt}")
            return
        }
        lastLocalSpeechKey = key
        lastLocalSpeechAt = now
        suppressModelForTurn = true
        val ownerTurnId = activeTurnId.takeIf { it != 0L }
            ?: responseArbiter.turnId.takeIf { it != 0L }
            ?: ++turnSequence
        responseArbiter.claimControlled(ownerTurnId)
        audio?.setBargeInEnabled(false)
        ordinaryModelAudioGate.onSpeechActivityEnded()
        earlyModelAudio.clear()
        earlyModelAudioBytes = 0L
        earlyModelAudioGenerationId = 0L
        voiceLog("suppression_start turnId=$ownerTurnId responseOwner=CONTROLLED_LOCAL reason=controlled_reply")
        // Remove any ordinary-model PCM already queued before the deterministic
        // correction/delete/recall response takes ownership.
        audio?.interrupt()
        localSpeechQueuedAt = now
        voiceLog(
            "local_speech_queued chars=${message.length} policy=${policyName(validationPolicy)} " +
                "alreadyValidating=${validatingLocalSpeech != null} allowNoTranscript=$allowUntranscribedAudio " +
                "turnId=$ownerTurnId responseOwner=CONTROLLED_LOCAL localSpeechQueuedAt=$localSpeechQueuedAt"
        )
        // Keep the echo-cancelled microphone open so the user can interrupt or issue
        // the next short command without waiting for LYRA's acknowledgement to finish.
        audio?.setMuted(false)
        if (validatingLocalSpeech == null) {
            allowUntranscribedLocalSpeech = allowUntranscribedAudio
            localSpeechValidationPolicy = validationPolicy
            // The turn owner suppresses ordinary output, and the existing transcript
            // validation gate will not release unmatched late PCM as controlled speech.
            // The former MEMORY quarantine added a fixed 2-second delay even after the
            // database-backed reply was ready, without adding another playback check.
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
            voiceLog("local_speech_unavailable reason=no_live_client chars=${message.length}")
            finishUnavailableNaturalLocalSpeech(message)
            return
        }
        if (!retry) localSpeechValidationAttempt = 0
        localSpeechValidationAttempt++
        localSpeechValidationToken++
        val token = localSpeechValidationToken
        controlledGenerationId++
        audio?.setPlaybackContext(controlledGenerationId, responseOwner = "CONTROLLED_LOCAL")
        validatingLocalSpeech = message
        localSpeechHasContent = false
        localSpeechStreamedDirectly = false
        localSpeechGenerationComplete = false
        localSpeechAudio.clear()
        localSpeechTranscript.clear()
        localSpeechFirstAudioReceivedAt = 0L
        localSpeechFirstAudioAcceptedAt = 0L
        localSpeechFirstPlaybackWriteAt = 0L
        localSpeechLastAudioReceivedAt = 0L
        suppressModelForTurn = true
        val generationStartAt = android.os.SystemClock.elapsedRealtime()
        voiceLog(
            "local_speech_generation_start turnId=${responseArbiter.turnId} generationId=$controlledGenerationId token=$token attempt=$localSpeechValidationAttempt " +
                "chars=${message.length} policy=${policyName(localSpeechValidationPolicy)} generationStartAt=$generationStartAt " +
                "queuedToGenerationStartMs=${generationStartAt - localSpeechQueuedAt}"
        )
        // Continuous mic packets can race with clientContent and cancel this short
        // deterministic memory utterance before Gemini returns audio. Listening is
        // restored by every playback-complete and unavailable-audio path below.
        if (localSpeechValidationPolicy.isolateFromMicDuringGeneration) {
            audio?.setMuted(true)
        }
        localSpeechRequestSentAt = android.os.SystemClock.elapsedRealtime()
        client.sendText("Say exactly these words once, with the selected natural voice. Do not add, remove, translate, explain, or introduce them: ${org.json.JSONObject.quote(message)}")
        voiceLog(
            "controlled_request_sent turnId=${responseArbiter.turnId} generationId=$controlledGenerationId token=$token " +
                "controlledRequestSentAt=$localSpeechRequestSentAt queuedToRequestSentMs=${localSpeechRequestSentAt - localSpeechQueuedAt}"
        )
        cancelLocalSpeechTimeout("new_generation")
        localSpeechTimeoutToken = token
        localSpeechTimeoutGate.start(token)
        val timeoutRunnable = Runnable {
            val timeoutFiredAt = android.os.SystemClock.elapsedRealtime()
            if (token == localSpeechValidationToken && validatingLocalSpeech != null &&
                localSpeechTimeoutGate.shouldFire(token)
            ) {
                voiceLog(
                    "local_speech_timeout turnId=${responseArbiter.turnId} generationId=$controlledGenerationId token=$token timeoutFiredAt=$timeoutFiredAt audioChunks=${localSpeechAudio.size} " +
                        "audioBytes=${localSpeechAudio.sumOf { it.size }} transcriptChars=${localSpeechTranscript.length}"
                )
                finishValidatedLocalSpeech()
            } else {
                voiceLog("local_speech_timeout_ignored token=$token activeToken=$localSpeechValidationToken reason=stale_generation")
            }
        }
        localSpeechTimeoutRunnable = timeoutRunnable
        val timeoutScheduledAt = android.os.SystemClock.elapsedRealtime()
        voiceLog("local_speech_timeout_scheduled generationId=$controlledGenerationId timeoutToken=$token timeoutScheduledAt=$timeoutScheduledAt")
        mainHandler.postDelayed(timeoutRunnable, localSpeechValidationPolicy.timeoutMs)
    }

    private fun startLocalSpeechWhenPrefixMatches() {
        val expected = validatingLocalSpeech ?: return
        if (localSpeechStreamedDirectly || localSpeechAudio.isEmpty()) return
        val actualForValidation = romanDisplayText(localSpeechTranscript.toString())
        val expectedForValidation = romanDisplayText(expected)
        if (!LocalSpeechGate.shouldReleaseBeforeTurnComplete(
                localSpeechValidationPolicy.bufferUntilValidated,
                actualForValidation,
                expectedForValidation
            )) {
            voiceLog(
                "local_speech_waiting_for_validation audioChunks=${localSpeechAudio.size} " +
                    "transcriptChars=${localSpeechTranscript.length}"
            )
            return
        }

        localSpeechStreamedDirectly = true
        localPlaybackActive = true
        localSpeechFirstAudioAcceptedAt = android.os.SystemClock.elapsedRealtime()
        if (localSpeechTimeoutGate.acceptFirstAudio(localSpeechValidationToken)) {
            cancelLocalSpeechTimeout("first_audio_accepted")
        }
        voiceLog(
            "local_speech_released_early turnId=${responseArbiter.turnId} generationId=$controlledGenerationId " +
                "audioChunks=${localSpeechAudio.size} firstAudioAcceptedAt=$localSpeechFirstAudioAcceptedAt"
        )
        localSpeechAudio.forEach { audio?.queueAudio(it) }
        localSpeechFirstPlaybackWriteAt = android.os.SystemClock.elapsedRealtime()
        voiceLog(
            "controlled_first_playback_write turnId=${responseArbiter.turnId} generationId=$controlledGenerationId " +
                "firstPlaybackWriteAt=$localSpeechFirstPlaybackWriteAt queuedToFirstPlaybackMs=${localSpeechFirstPlaybackWriteAt - localSpeechQueuedAt}"
        )
        localSpeechAudio.clear()
    }

    private fun cancelLocalSpeechTimeout(reason: String) {
        val runnable = localSpeechTimeoutRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        localSpeechTimeoutRunnable = null
        localSpeechTimeoutGate.clear(localSpeechTimeoutToken)
        voiceLog(
            "local_speech_timeout_cancelled turnId=${responseArbiter.turnId} generationId=$controlledGenerationId " +
                "timeoutToken=$localSpeechTimeoutToken timeoutCancelledAt=${android.os.SystemClock.elapsedRealtime()} reason=$reason"
        )
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
        val normalizedActual = romanDisplayText(actual)
        val normalizedExpected = romanDisplayText(expected)
        val transcriptMatches = LocalSpeechGate.matchesExpectedExactly(normalizedActual, normalizedExpected)
        // Memory prompts are low-risk and already have their exact text on screen. Live
        // sometimes streams the selected natural voice before its output transcript. In
        // that narrow case, keep the buffered Gemini audio instead of discarding it and
        // switching to robotic Android TTS. Phone actions retain strict transcript gating.
        val bufferedAudioBytes = localSpeechAudio.sumOf { it.size }
        val trustedNaturalAudio =
            localSpeechValidationPolicy.trustBufferedNaturalAudio &&
                localSpeechHasContent &&
                LocalSpeechGate.hasEnoughBufferedNaturalAudio(bufferedAudioBytes, expected)
        voiceLog(
            "local_speech_validation_result transcriptMatch=$transcriptMatches " +
                "trustedAudio=$trustedNaturalAudio hasContent=$localSpeechHasContent " +
                "audioBytes=$bufferedAudioBytes actualChars=${actual.length} expectedChars=${expected.length} " +
                "normalizedActual=${normalizedActual.take(120)} normalizedExpected=${normalizedExpected.take(120)}"
        )
        if ((transcriptMatches || trustedNaturalAudio) && localSpeechAudio.isNotEmpty()) {
            if (localSpeechTimeoutGate.acceptFirstAudio(localSpeechValidationToken)) {
                cancelLocalSpeechTimeout("validated_audio_accepted")
            }
            localSpeechGenerationComplete = true
            localPlaybackActive = true
            voiceLog("local_speech_released_after_validation audioChunks=${localSpeechAudio.size}")
            localSpeechAudio.forEach { audio?.queueAudio(it) }
            localSpeechFirstPlaybackWriteAt = android.os.SystemClock.elapsedRealtime()
            voiceLog(
                "controlled_first_playback_write turnId=${responseArbiter.turnId} generationId=$controlledGenerationId " +
                    "firstPlaybackWriteAt=$localSpeechFirstPlaybackWriteAt queuedToFirstPlaybackMs=${localSpeechFirstPlaybackWriteAt - localSpeechQueuedAt}"
            )
            localSpeechAudio.clear()
            localSpeechTranscript.clear()
        } else {
            localSpeechAudio.clear()
            localSpeechTranscript.clear()
            if (localSpeechValidationAttempt < localSpeechValidationPolicy.maxAttempts && live != null) {
                voiceLog("local_speech_retry nextAttempt=${localSpeechValidationAttempt + 1}")
                beginValidatedLocalSpeech(expected, retry = true)
            } else {
                voiceLog("local_speech_dropped reason=validation_failed attempts=$localSpeechValidationAttempt")
                finishUnavailableNaturalLocalSpeech(expected)
            }
        }
    }

    private fun finishUnavailableNaturalLocalSpeech(message: String) {
        cancelLocalSpeechTimeout("natural_audio_unavailable")
        voiceLog(
            "local_speech_unavailable chars=${message.length} fallback=${localSpeechValidationPolicy.speakFallback} " +
                "allowNoTranscript=$allowUntranscribedLocalSpeech"
        )
        // Never switch to Android TTS. If validated natural Gemini audio is
        // unavailable, preserve the already-visible deterministic text, complete
        // any deferred verified action, and resume listening silently.
        allowUntranscribedLocalSpeech = false
        localPlaybackActive = false
        localSpeechStreamedDirectly = false
        localSpeechGenerationComplete = false
        responseArbiter.controlledGenerationComplete()
        responseArbiter.controlledPlaybackComplete()
        if (responseArbiter.releaseIfComplete()) voiceLog("suppression_end turnId=${responseArbiter.turnId} reason=unavailable_natural_audio")
        if (!runPendingActionAfterSpeech()) {
            audio?.setMuted(false)
            emitState("Sun rahi hoon…")
        }
    }

    private fun finishLocalPlayback() {
        val playbackEndAt = android.os.SystemClock.elapsedRealtime()
        voiceLog("local_speech_playback_finished turnId=${responseArbiter.turnId} generationId=$controlledGenerationId playbackEndAt=$playbackEndAt")
        val resumeMicImmediately =
            localSpeechValidationPolicy.resumeMicImmediatelyAfterPlayback
        allowUntranscribedLocalSpeech = false
        localPlaybackActive = false
        localSpeechStreamedDirectly = false
        localSpeechGenerationComplete = false
        responseArbiter.controlledPlaybackComplete()
        if (responseArbiter.releaseIfComplete()) {
            voiceLog("suppression_end turnId=${responseArbiter.turnId} generationId=$controlledGenerationId reason=matching_generation_and_playback_complete")
        }
        if (!runPendingActionAfterSpeech()) {
            if (resumeMicImmediately) audio?.resumeListeningNow()
            else audio?.setMuted(false)
            emitState("Sun rahi hoon…")
        }
    }

    private fun policyName(policy: LocalSpeechValidationPolicy): String = when (policy) {
        LocalSpeechValidationPolicy.MEMORY -> "MEMORY"
        LocalSpeechValidationPolicy.DEFAULT -> "DEFAULT"
        else -> "CUSTOM"
    }

    private fun voiceLog(message: String) {
        if (VOICE_AUDIO_DEBUG_LOGGING) {
            VoicePipelineLogger.debug(message)
        }
    }

    private fun commitFinalUserMessage(
        raw: String,
        source: String,
        normalized: String = romanDisplayText(raw),
        display: String = normalized
    ) {
        val turnId = activeTurnId.takeIf { it != 0L }
            ?: responseArbiter.turnId.takeIf { it != 0L }
            ?: ++turnSequence
        val utteranceId = "$transcriptSessionId:$turnId"
        voiceLog(
            "user_message_commit_attempt sessionId=$transcriptSessionId turnId=$turnId " +
                "utteranceId=$utteranceId source=$source raw=${raw.take(160)} " +
                "normalized=${normalized.take(160)} display=${display.take(160)}"
        )
        when (val result = finalUserMessageCommitter.commit(
            FinalUserMessage(transcriptSessionId, turnId, utteranceId, raw, normalized, display)
        )) {
            is UserMessageCommitResult.Accepted -> {
                voiceLog(
                    "user_message_commit_result sessionId=$transcriptSessionId turnId=$turnId " +
                        "utteranceId=$utteranceId source=$source accepted=true messageId=${result.messageId}"
                )
                listener?.onUserText(result.message.display)
            }
            is UserMessageCommitResult.AlreadyCommitted -> voiceLog(
                "user_message_commit_result sessionId=$transcriptSessionId turnId=$turnId " +
                    "utteranceId=$utteranceId source=$source accepted=false " +
                    "reason=already_committed existingMessageId=${result.existingMessageId}"
            )
        }
    }

    private fun resetTurnBuffers(reason: String = "turn_committed") {
        voiceLog(
            "transcript_accumulator_reset turnId=$activeTurnId session=${hashCode()} " +
                "reason=$reason inputChars=${input.length} commandChars=${commandProbe.length}"
        )
        input.clear()
        output.clear()
        commandProbe.clear()
        commandUserTextEmitted = false
        probableActionTurn = false
        mediaBlockedTurn = false
        ambiguousMessageTurn = false
        incompleteActionFragmentTurn = false
        activeTurnId = 0L
        if (!screenResponseActive) {
            speechTimingTurnId = 0L
            speechActivityStartedAt = 0L
            speechActivityEndedAt = 0L
        }
        if (!screenResponseActive) {
            armedScreenQuestion = ""
            armedScreenQuestionTurnId = 0L
            armedScreenQuestionDetectedAt = 0L
            earlyScreenQueryAwaitingFinalTranscript = false
            earlyScreenQueryDispatchedTurnId = 0L
        }
    }

    private fun romanDisplayText(value: String): String {
        if (Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF]").containsMatchIn(value)) {
            return "Voice input unclear - please repeat."
        }
        val transliterated = romanTransliterator?.transliterate(value)?.trim().orEmpty()
            .ifBlank { value.trim() }
        return RomanHinglishFormatter.format(transliterated)
    }

    private fun finalTranscriptDisplay(value: String): FinalTranscriptDisplayFormatter.Result {
        return FinalTranscriptDisplayFormatter.format(value) { token ->
            romanTransliterator?.transliterate(token)?.trim().orEmpty().ifBlank { token }
        }
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

    private fun rememberBestFriendForCorrection(candidate: MemoryCandidate) {
        lastSavedBestFriendName = MemoryRelationshipPolicy.personName(candidate.fact)
            ?.let(BestFriendNameCanonicalizer::canonicalize)
        lastSavedBestFriendAt = android.os.SystemClock.elapsedRealtime()
    }

    private fun replaceRecentRelationshipName(oldName: String, newName: String) {
        for (index in recentRelationshipTurns.indices) {
            val (time, text) = recentRelationshipTurns[index]
            recentRelationshipTurns[index] = time to text.replace(
                Regex("\\b${Regex.escape(oldName)}\\b", RegexOption.IGNORE_CASE),
                newName
            )
        }
    }

    private fun startCanonicalRename(correction: BestFriendNameCorrection) {
        val validationFailure = BestFriendNameCorrectionParser.validateNewName(correction.newName)
        if (validationFailure != null || correction.oldName.equals(correction.newName, ignoreCase = true)) {
            voiceLog(
                "name_correction_rejected oldNameCandidate=${correction.oldName} " +
                    "newNameCandidate=${correction.newName} newNameValidation=rejected " +
                    "rejectionReason=${validationFailure ?: "old_and_new_names_are_identical"} " +
                    "databaseMutationAllowed=false"
            )
            val clarification = "Correct naam clear nahi hua. Ek baar naam clearly repeat karo."
            suppressModelForTurn = true
            localCommandExecutedThisTurn = true
            output.clear()
            audio?.interrupt()
            listener?.onMyraText(clarification)
            emitState(clarification)
            queueLocalSpeech(clarification, allowUntranscribedAudio = true)
            return
        }
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        output.clear()
        audio?.interrupt()
        voiceLog(
            "name_correction_mutation oldNameCandidate=${correction.oldName} " +
                "newNameCandidate=${correction.newName} newNameValidation=valid " +
                "databaseMutationAllowed=true"
        )
        // Do not trust Gemini's conversational acknowledgement. Only this verified
        // repository result is allowed to produce a success bubble or spoken reply.
        pendingCanonicalRename = serviceScope.launch {
            val before = memoryRepository.logPersonIdentity(
                "before_correction", correction.oldName, correction.newName
            )
            voiceLog(
                "correction_transaction old=${correction.oldName} new=${correction.newName} " +
                    "matchingRowIds=${before.map { it.id }}"
            )
            val renamed = memoryRepository.renameBestFriend(correction.oldName, correction.newName)
            val rows = memoryRepository.logPersonIdentity(
                "after_correction renamed=$renamed", correction.oldName, correction.newName
            )
            val verified = renamed && rows.any {
                MemoryRelationshipPolicy.personName(it.fact)
                    ?.equals(correction.newName, ignoreCase = true) == true
            } && rows.none {
                MemoryRelationshipPolicy.personName(it.fact)
                    ?.equals(correction.oldName, ignoreCase = true) == true
            }
            voiceLog(
                "correction_transaction_result writeSuccess=$renamed verified=$verified " +
                    "successAcknowledgementAllowed=$verified " +
                    "finalRows=${rows.joinToString { "${it.id}:${it.stableKey}:${it.fact}" }}"
            )
            val successAcknowledgementAllowed = CorrectionSuccessPolicy.acknowledgementAllowed(
                writeSuccess = renamed,
                verified = verified
            )
            val reply = if (successAcknowledgementAllowed) {
                "Theek hai, ab ${correction.newName} naam save hai."
            } else {
                "Naam update nahi ho paya. Ek baar phir try karo."
            }
            mainHandler.post {
                if (successAcknowledgementAllowed) {
                    replaceRecentRelationshipName(correction.oldName, correction.newName)
                    lastSavedBestFriendName = correction.newName
                    lastSavedBestFriendAt = android.os.SystemClock.elapsedRealtime()
                    voiceLog("correction_cache_invalidated old=${correction.oldName} new=${correction.newName}")
                }
                listener?.onMyraText(reply)
                emitState(reply)
                queueLocalSpeech(
                    reply,
                    allowUntranscribedAudio = true,
                    validationPolicy = LocalSpeechValidationPolicy.MEMORY
                )
            }
        }
    }

    private fun isPhantomTranscript(value: String): Boolean {
        return PhantomTranscriptFilter.shouldIgnore(value)
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
        val genderStyle = "$baseGenderStyle ${FriendConversationPolicy.BOSS_ASSISTANT_STYLE} When natural conversation clearly reveals one durable fact about the user, call propose_user_memory once with the user's actual supporting words. Never call it for guesses, temporary feelings, secrets, or information already present in saved memory; never claim it was saved or ask permission yourself. The user may have multiple best friends. When an explicit completed statement names another best friend, accept it naturally and never ask which name is correct, whether to replace someone, or whether the user is sure; Android adds each named person silently. Never interpret delete, remove, or hata do as uninstalling an Android app. App uninstall is unsupported. If Android does not handle an unclear delete request, ask what memory or item the user means. When current Screen Vision frames are present, answer screen questions only from visible evidence. Never claim to see the screen without a current frame. For an explicit visible-target request, call perform_screen_action so Android accessibility selects and verifies the existing UI target; never invent coordinates or claim success before verification. Call propose_screen_memory only for a durable, non-sensitive project, goal, or preference that is directly evidenced on the screen. Never propose credentials, private messages, banking or health data, or temporary UI state."
        val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.getDefault()).format(Date())
        return "You are LYRA speaking ALOUD to $name. Current date/time: $now. $style $genderStyle Keep the same identity, voice character, and grammatical gender for the entire Live session, including after Android opens or closes another app. Conversation mode begins when the Live session connects, so do not require a wake word again during that session. Behave like a close friend in a natural voice call, not a command-response bot or customer-support agent. Silence is normal: never speak merely because there is silence, background noise, a breath, a filler sound, or an incomplete fragment. Wait until the user has completed a meaningful thought before answering, and never cut them off mid-thought. Do not respond to every sentence when listening is more natural. Brief reactions such as Hmm, acha, I see, or seriously may be used occasionally only after clear meaningful speech, never automatically or repeatedly. Express emotion through the natural voice, not by announcing emotion or writing stage directions. Match vocal delivery to both the user's mood and the meaning of the conversation: sound brighter, warmer, and slightly more energetic for happiness or exciting news; softer, slower, and gently reassuring for sadness, worry, or vulnerability; calm, steady, and patient for frustration or anger; lightly teasing and playful during mutual joking; naturally surprised when something is genuinely unexpected; and focused with less playfulness for serious topics. Emotional changes must be subtle and human, never theatrical. Never fake sobbing, crying sounds, panic, jealousy, guilt, or emotional dependence. Do not mirror intense anger back at the user. When uncertain about mood, use a warm neutral voice. Ask at most one natural follow-up when it adds value, show genuine curiosity sometimes, and continue the active conversation using its existing context. Avoid robotic phrases such as How may I assist you, Is there anything else I can help with, and Your request has been completed. Never initiate an unprompted conversational reply unless Android delivers an explicit supported event such as a WhatsApp notification. Android executes phone actions locally. Infer natural and indirect intent from English, Hindi, Urdu, and Roman Hinglish. When the user clearly wants one supported phone action, call perform_phone_action even if they did not use command wording. Examples: wanting to watch something means PLAY_YOUTUBE; wanting YouTube short videos means OPEN_YOUTUBE_SHORTS; wanting Instagram reels means REQUEST_INSTAGRAM_REELS. For scrolling, the plain words scroll or scroll karo always mean SCROLL_REPEAT. Use SCROLL_DOWN only when the user explicitly says down, niche, or neeche; use SCROLL_UP only when they explicitly say up, upar, or upper. Ask one brief natural follow-up when the intended action, app, query, recipient, or direction is uncertain. Never call a tool for a hypothetical question or casual mention. Remember, forget, and what-do-you-remember requests are memory intent, never phone actions. Never send WhatsApp messages through tools. For every phone action: produce no audio and no confirmation before or after the tool call; Android reports the deterministic local result. Never invent device state, notification, contact, message, delivery, or successful phone action."
    }

    private fun markUserInteraction() {
        idleNudgeCount = 0
        mainHandler.removeCallbacks(idleNudgeRunnable)
        // Silence is normal. Do not schedule an unsolicited conversation starter.
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
    private fun stopSession() { isNaturalVoiceReady = false; connectionPreparing = false; pendingActionAfterLocalSpeech = null; readingTracker.stop(); screenCommandTurnGuard.clear(); mainHandler.removeCallbacks(idleNudgeRunnable); mainHandler.removeCallbacks(memoryCommandRunnable); mainHandler.removeCallbacks(personalMemoryPauseRunnable); pendingMemoryCommand = null; pendingDeleteClarificationUntil = 0L; pendingDetectedPersonalMemory = null; pendingPersonalMemory = null; pendingPersonalMemoryExpiresAt = 0L; pendingPersonalMemoryConfirmationInput.clear(); recentRelationshipTurns.clear(); serviceScope.cancel(); mediaGuard.release(); live?.disconnect(); audio?.release(); wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null; live = null; audio = null; isRunning = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() {
        ScreenCaptureService.listeners -= screenCaptureListener
        instance = null
        if (isRunning) stopSession()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.myra.START_VOICE"
        const val ACTION_STOP = "com.myra.STOP_VOICE"
        const val ACTION_MUTE = "com.myra.MUTE_VOICE"
        const val EXTRA_MUTED = "muted"
        private const val CHANNEL_ID = "myra_voice"
        private const val NOTIFICATION_ID = 1001
        private const val MEMORY_COMMAND_PAUSE_MS = 450L
        private const val DELETE_CLARIFICATION_TIMEOUT_MS = 30_000L
        private const val PERSONAL_MEMORY_PAUSE_MS = 450L
        private const val PERSONAL_MEMORY_CONFIRMATION_MS = 30_000L
        private const val LOCAL_SPEECH_AUDIO_DRAIN_MS = 800L
        private const val SCREEN_QUERY_DIAGNOSTIC_TIMEOUT_MS = 8_000L
        private const val MAX_READING_CHARS_PER_SCREEN = 1_200
        private const val RELATIONSHIP_CONTEXT_MS = 45_000L
        private const val MAX_RELATIONSHIP_CONTEXT_TURNS = 3
        private const val BEST_FRIEND_CORRECTION_CONTEXT_MS = 45_000L
        private const val FIRST_IDLE_NUDGE_MS = 2 * 60 * 1000L
        private const val SECOND_IDLE_NUDGE_MS = 5 * 60 * 1000L
        private const val IDLE_RECHECK_MS = 30 * 1000L
        private const val MAX_IDLE_NUDGES = 2
        private const val VOICE_AUDIO_DEBUG_LOGGING = true
        private const val VOICE_AUDIO_LOG_TAG = "LyraVoicePipeline"
        @Volatile var isRunning = false
        @Volatile var isNaturalVoiceReady = false
        @Volatile var listener: Listener? = null
        @Volatile private var uiVisible = false
        @Volatile private var instance: MyraVoiceService? = null
        fun sendText(text: String) {
            instance?.let {
                it.markUserInteraction()
                it.lastUserIntentText = text.trim()
                val reading = ReadingIntentParser.parse(text)
                val typedTurnId = ++it.turnSequence
                val screenIntent = ScreenVisionIntentParser.parse(text)
                if (reading != null && it.handleReadingCommand(reading, typedTurnId)) {
                    Unit
                } else if (screenIntent != null) {
                    it.beginFreshScreenQuery(text, typedTurnId)
                } else if (!it.handleExplicitMemoryText(text)) {
                    it.live?.sendText(text)
                }
                Unit
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
                service.voiceLog("ui_visibility visible=$visible")
                service.mainHandler.removeCallbacks(service.idleNudgeRunnable)
                if (visible) service.markUserInteraction()
            }
        }
        fun interrupt() { instance?.audio?.interrupt(); instance?.live?.interrupt() }
    }
}
