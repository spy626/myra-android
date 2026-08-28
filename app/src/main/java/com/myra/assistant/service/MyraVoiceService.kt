Warning: truncated output (original token count: 43668)
Total output lines: 2926

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
import com.myra.assistant.screen.ScreenVisionPreferences
import com.myra.assistant.screen.FreshFrameResult
import com.myra.assistant.screen.ScreenResponseBinding
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
                        screenResponseTextCommitted = true
                    }
                    else voiceLog("screen_query_result_dropped_stale screen_query_id=$screenResponseQueryId screen_session_id=$screenResponseSessionId reason=${if (!current) "stopped_session" else "empty_result"}")
                    voiceLog("screen_response_generation_complete screen_query_id=$screenResponseQueryId screen_session_id=$screenResponseSessionId current=$current")
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
         …13668 tokens truncated…                 live?.sendToolResponse(id, "propose_user_memory", true, "Saved silently; continue the conversation naturally without mentioning memory")
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

    private fun fallbackLocalSpeech(message: String) {
        assistantController.speakMessage(message) {
            if (!runPendingActionAfterSpeech()) {
                audio?.setMuted(false)
                emitState("Sun rahi hoon…")
            }
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
        val genderStyle = "$baseGenderStyle When natural conversation clearly reveals one durable fact about the user, call propose_user_memory once with the user's actual supporting words. Never call it for guesses, temporary feelings, secrets, or information already present in saved memory; never claim it was saved or ask permission yourself. The user may have multiple best friends. When an explicit completed statement names another best friend, accept it naturally and never ask which name is correct, whether to replace someone, or whether the user is sure; Android adds each named person silently. Never interpret delete, remove, or hata do as uninstalling an Android app. App uninstall is unsupported. If Android does not handle an unclear delete request, ask what memory or item the user means. When current Screen Vision frames are present, answer screen questions only from visible evidence. Never claim to see the screen without a current frame. For an explicit visible-target request, call perform_screen_action so Android accessibility selects and verifies the existing UI target; never invent coordinates or claim success before verification. Call propose_screen_memory only for a durable, non-sensitive project, goal, or preference that is directly evidenced on the screen. Never propose credentials, private messages, banking or health data, or temporary UI state."
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
    private fun stopSession() { isNaturalVoiceReady = false; connectionPreparing = false; mainHandler.removeCallbacks(idleNudgeRunnable); mainHandler.removeCallbacks(memoryCommandRunnable); mainHandler.removeCallbacks(personalMemoryPauseRunnable); pendingMemoryCommand = null; pendingDeleteClarificationUntil = 0L; pendingDetectedPersonalMemory = null; pendingPersonalMemory = null; pendingPersonalMemoryExpiresAt = 0L; pendingPersonalMemoryConfirmationInput.clear(); recentRelationshipTurns.clear(); serviceScope.cancel(); mediaGuard.release(); live?.disconnect(); audio?.release(); wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null; live = null; audio = null; isRunning = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
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
                val screenIntent = ScreenVisionIntentParser.parse(text)
                if (screenIntent != null) {
                    it.beginFreshScreenQuery(text, ++it.turnSequence)
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
