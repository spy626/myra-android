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
import com.myra.assistant.screen.ScreenModeCommand
import com.myra.assistant.screen.ScreenModeCommandParser
import com.myra.assistant.screen.ScreenVisionIntentParser
import com.myra.assistant.screen.InstantScreenQuery
import com.myra.assistant.screen.ScreenCacheUse
import com.myra.assistant.screen.ScreenContextStore
import com.myra.assistant.screen.HotScreenCachePolicy
import com.myra.assistant.screen.ScreenVisionPreferences
import com.myra.assistant.screen.VisualAwarenessPreferences
import com.myra.assistant.screen.FastVisualKind
import com.myra.assistant.screen.FastVisualRequest
import com.myra.assistant.screen.FastVisualRequestClassifier
import com.myra.assistant.screen.FastVisualTurnCoordinator
import com.myra.assistant.screen.VisualAcquisitionGate
import com.myra.assistant.screen.VisualScreenshotTimeoutPolicy
import com.myra.assistant.screen.SemanticScreenFallbackPolicy
import com.myra.assistant.screen.VisibleScreenElement
import com.myra.assistant.agent.ActivityContextStore
import com.myra.assistant.agent.UnifiedLyraAgentRuntime
import com.myra.assistant.agent.TurnIntent
import com.myra.assistant.agent.WorkingTaskRuntime
import com.myra.assistant.agent.BrowserSearchRequestParser
import com.myra.assistant.agent.BrowserSearchTool
import com.myra.assistant.agent.SearchExecutionPolicy
import com.myra.assistant.agent.SearchDestination
import com.myra.assistant.agent.SearchDestinationResolver
import com.myra.assistant.agent.BrowserSearchVerificationPolicy
import com.myra.assistant.agent.YouTubeSearchVerificationPolicy
import com.myra.assistant.agent.SearchTaskResultPolicy
import com.myra.assistant.agent.SearchVerification
import com.myra.assistant.agent.TaskCompletionState
import com.myra.assistant.agent.AgentToolRegistry
import com.myra.assistant.agent.GeneralActionResult
import com.myra.assistant.agent.GeneralActionRouter
import com.myra.assistant.agent.GeneralAgentRuntimeStore
import com.myra.assistant.agent.GeneralRuntimeTask
import com.myra.assistant.agent.GeneralToolAdapter
import com.myra.assistant.agent.GeneralVerificationStatus
import com.myra.assistant.agent.PerceptionSnapshot
import com.myra.assistant.agent.PlannerResult
import com.myra.assistant.agent.ProductionAdapterExecutors
import com.myra.assistant.agent.ProductionGeneralAdapters
import com.myra.assistant.agent.RecoveryDecision
import com.myra.assistant.agent.ScreenSceneFactory
import com.myra.assistant.agent.ScrollMovementAnalyzer
import com.myra.assistant.agent.ScrollVerificationResamplePolicy
import com.myra.assistant.agent.ToolCapability
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.myra.assistant.agent.TextComposeSession
import com.myra.assistant.screen.YouTubeSemanticCommand
import com.myra.assistant.screen.YouTubeSemanticCommandParser
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
    private val textComposeSession = TextComposeSession()
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
    private val visualDeadlineExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "lyra-visual-deadline").apply { isDaemon = true }
    }
    private val visualFrameDeliveryExecutor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue(),
        java.util.concurrent.ThreadFactory { runnable ->
            Thread(runnable, "lyra-current-visual-delivery").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
            }
        }
    )
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
    private val voiceTurnIdentities = VoiceTurnIdentityStore()
    private val pendingScrollCandidates = PendingScrollCandidateStore()
    private var inputTurnStartedAt = 0L
    private var latestTurnAcceptedAt = 0L
    private var latestIntentDecidedAt = 0L
    private var latestIntentTimingTurnId = 0L
    private var latestActionDispatchedAt = 0L
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
    private var screenResponseAccessibilityPackage = ""
    private var screenResponseAccessibilityGeneration = 0L
    private var screenResponseQueryId = ""
    private var screenQuestionDetectedAt = 0L
    private var screenFreshFrameCapturedAt = 0L
    private var screenFrameSentAt = 0L
    private var screenResponseSpeechEndedAt = 0L
    private var screenQuerySpeechTurnConsistency = false
    private val fastVisualTurns = FastVisualTurnCoordinator()
    private var armedScreenQuestion = ""
    private var armedScreenQuestionTurnId = 0L
    private var armedScreenQuestionDetectedAt = 0L
    private var armedScreenQuestionFinalCommitted = false
    private var earlyScreenQuestionText = ""
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
    private val generalToolRegistry = AgentToolRegistry()
    private val generalActionRouter by lazy {
        GeneralActionRouter(ProductionGeneralAdapters.create(
            generalToolRegistry,
            ProductionAdapterExecutors(
                scroll = { step, _ -> executeGeneralScrollAdapter(step.parameters) },
                browserSearch = { step, _ -> executeGeneralBrowserSearchAdapter(step.parameters) },
                observeScreen = { _, _ -> GeneralActionResult(ActivityContextStore.snapshot() != null) },
                verifyScreen = { _, _ -> GeneralActionResult(ActivityContextStore.snapshot() != null) },
                back = { _, _ ->
                    val accepted = AccessibilityHelperService.instance?.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                    ) == true
                    GeneralActionResult(accepted, failureReason = "back_dispatch_rejected".takeIf { !accepted })
                },
                findElement = { step, perception -> executeGeneralFindElementAdapter(step, perception) },
                tapElement = { step, perception -> executeGeneralSemanticTapAdapter(step, perception) }
            )
        ))
    }
    private val memoryRepository by lazy { MemoryRepository(LyraMemoryDatabase.get(this).memoryDao()) }
    private val assistantController by lazy { (application as MyApplication).assistantController }
    private val screenVisionPreferences by lazy { ScreenVisionPreferences(this) }
    private val visualAwarenessPreferences by lazy { VisualAwarenessPreferences(this) }
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
        if (state != ScreenShareState.ACTIVE && screenResponseActive && !screenResponseSessionId.startsWith("accessibility:")) {
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
                        audio?.queueAudio(pcm, controlledGenerationId, "CONTROLLED_LOCAL")
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
                else if (screenResponseActive && isScreenResponseContextCurrent()) {
                    val generationAccepted = screenResponseBinding?.acceptsGeneration(modelGenerationId) == true
                    screenResponseGenerationId = screenResponseBinding?.screenGenerationId ?: 0L
                    if (!generationAccepted) {
                        voiceLog(
                            "SCREEN_RESPONSE_DECISION visualTurnId=${fastVisualTurns.current()?.id.orEmpty()} " +
                                "currentTurn=$screenResponseUserTurnId generationId=$modelGenerationId owner=CONTROLLED_SCREEN " +
                                "decision=DROP reason=stale_generation"
                        )
                        voiceLog("screen_query_result_dropped_stale screen_query_id=$screenResponseQueryId modelGenerationId=$modelGenerationId expectedAfter=$screenResponseAfterGenerationId boundGenerationId=$screenResponseGenerationId")
                    } else {
                        if (!screenResponseStartedLogged) {
                            screenResponseStartedLogged = true
                            voiceLog("screen_query_state screenQueryId=$screenResponseQueryId state=RESPONSE_STARTED source=AUDIO modelGenerationId=$modelGenerationId")
                            fastVisualTurns.current()?.takeIf { it.userTurnId == screenResponseUserTurnId }?.let {
                                it.firstModelResponseAt = audioReceivedAt
                                it.firstAudioAt = audioReceivedAt
                                it.replyQueuedAt = audioReceivedAt
                                voiceLog(
                                    "visual_model_first_response visualTurnId=${it.id} source=AUDIO " +
                                        "modelRequestToFirstResponseMs=${if (it.modelRequestAt > 0L) audioReceivedAt - it.modelRequestAt else -1L}"
                                )
                                voiceLog("visualModelFirstStructuredResult visualTurnId=${it.id} source=AUDIO at=$audioReceivedAt")
                                voiceLog("replyQueued visualTurnId=${it.id} at=$audioReceivedAt owner=CONTROLLED_SCREEN")
                                voiceLog(
                                    "visual_reply_audio_started visualTurnId=${it.id} " +
                                        "speechEndToFirstAudioMs=${if (it.speechEndedAt > 0L) audioReceivedAt - it.speechEndedAt else -1L}"
                                )
                            }
                        }
                        screenResponseHasContent = true
                        mediaGuard.beginAssistantTurn()
                        audio?.setPlaybackContext(modelGenerationId, screenResponseQueryId, "CONTROLLED_SCREEN")
                        audio?.setBargeInEnabled(true)
                        audio?.queueAudio(pcm, modelGenerationId, "CONTROLLED_SCREEN")
                        voiceLog(
                            "SCREEN_RESPONSE_DECISION visualTurnId=${fastVisualTurns.current()?.id.orEmpty()} " +
                                "currentTurn=$screenResponseUserTurnId generationId=$modelGenerationId owner=CONTROLLED_SCREEN " +
                                "decision=PLAY reason=current_bound_generation"
                        )
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
                            audio?.queueAudio(pcm, modelGenerationId, "MODEL")
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
                    if (activeTurnId == 0L) activeTurnId = ++turnSequence
                    inputTurnStartedAt = android.os.SystemClock.elapsedRealtime()
                    if (speechTimingTurnId == 0L && speechActivityStartedAt > 0L) speechTimingTurnId = activeTurnId
                    if (responseArbiter.turnId != activeTurnId) responseArbiter.begin(activeTurnId)
                    acceptedModelGenerationForTurn = 0L
                    modelAudioDroppedBeforeTurnCompleteCount = 0
                    modelAudioDroppedBeforeTurnCompleteBytes = 0L
                    voiceLog(
                        "input_turn_started turnId=$activeTurnId session=${hashCode()} inputTurnStartedAt=$inputTurnStartedAt " +
                            "speechActivityStartedAt=$speechActivityStartedAt latestModelGenerationId=$latestModelGenerationId " +
                            "voiceTurnConsistent=${voiceTurnIdentities.current()?.userTurnId == activeTurnId}"
                    )
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
                            val ownerDecision = com.myra.assistant.agent.UnifiedTurnInterpreter.interpret(
                                spoken, WorkingTaskRuntime.store.snapshot()
                            )
                            if (!ownerDecision.authorizesPhoneActions) {
                                voiceLog("direct_media_action_rejected_by_unified_owner turnId=$activeTurnId intent=${ownerDecision.intent}")
                                return@inputTranscript
                            }
                            if (directCommand is AppCommand.ScrollYouTube) {
                                handleScrollProposal(
                                    directCommand, "media_pre_final", ScrollProposalAuthorization.PRE_FINAL
                                )
                                mediaBlockedTurn = false
                                return@inputTranscript
                            }
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
                if (BrowserSearchRequestParser.parse(currentTranscript) != null) {
                    // Search is resolved only at FINAL, but speculative conversational
                    // audio must not ask for a destination after a contextual action has
                    // already been authorized and executed.
                    suppressModelForTurn = true
                    output.clear()
                    audio?.interrupt()
                    voiceLog("search_turn_reserved turnId=$activeTurnId source=PARTIAL_FINAL_REQUIRED")
                }
                if (ScreenVisionIntentParser.parse(currentScreenText) != null ||
                    FastVisualRequestClassifier.classify(currentTranscript) != null
                ) {
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
                val command = (CommandParser.parse(part) ?: CommandParser.parse(commandProbe.toString()))
                    ?.takeUnless { it is AppCommand.SearchYouTube }
                if (CommandParser.isProbableDeviceAction(part) || CommandParser.isProbableDeviceAction(commandProbe.toString())) {
                    probableActionTurn = true
                    suppressModelForTurn = true
                    output.clear()
                }
                // A streamed transcript may first contain only "YouTube" and later add
                // "mein search karo Lols Gaming". Never execute a plain open-app command
                // from an incomplete chunk; confirm it from the complete turn below.
                val explicitOpen = command is AppCommand.OpenApp && CommandParser.isExplicitOpenCommand(part)
                // Never execute an ordinary phone action from a partial transcript. A later
                // chunk can turn "open Chrome" into a discussion about opening Chrome. The
                // complete FINAL utterance must pass UnifiedTurnInterpreter first.
                if (command != null && (command !is AppCommand.OpenApp || explicitOpen) && command !is AppCommand.DeepResearch) {
                    probableActionTurn = true
                    suppressModelForTurn = true
                    output.clear()
                    val candidateName = if (command is AppCommand.ScrollYouTube && command.explicitlyRequestedApp == null) {
                        "GenericScroll"
                    } else command.javaClass.simpleName
                    voiceLog("partial_action_held_for_unified_owner turnId=$activeTurnId candidate=$candidateName")
                    if (command is AppCommand.ScrollYouTube) {
                        handleScrollProposal(command, "partial_transcript", ScrollProposalAuthorization.PRE_FINAL)
                    }
                }
            }
            client.onOutputTranscript = { transcript, modelGenerationId ->
                if (validatingLocalSpeech != null) {
                    localSpeechHasContent = true
                    appendTranscript(localSpeechTranscript, transcript)
                    startLocalSpeechWhenPrefixMatches()
                }
                else if (screenResponseActive && isScreenResponseContextCurrent()) {
                    if (screenResponseBinding?.acceptsGeneration(modelGenerationId) == true) {
                        screenResponseGenerationId = screenResponseBinding?.screenGenerationId ?: 0L
                        if (!screenResponseStartedLogged) {
                            screenResponseStartedLogged = true
                            voiceLog("screen_query_state screenQueryId=$screenResponseQueryId state=RESPONSE_STARTED source=TEXT modelGenerationId=$modelGenerationId")
                            val now = android.os.SystemClock.elapsedRealtime()
                            fastVisualTurns.current()?.takeIf { it.userTurnId == screenResponseUserTurnId }?.let {
                                it.firstModelResponseAt = now
                                it.replyQueuedAt = now
                                voiceLog(
                                    "visual_model_first_response visualTurnId=${it.id} source=TEXT " +
                                        "modelRequestToFirstResponseMs=${if (it.modelRequestAt > 0L) now - it.modelRequestAt else -1L}"
                                )
                                voiceLog("visualModelFirstStructuredResult visualTurnId=${it.id} source=TEXT at=$now")
                                voiceLog("replyQueued visualTurnId=${it.id} at=$now owner=CONTROLLED_SCREEN")
                            }
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
                        if (com.myra.assistant.screen.EarlyScreenQuestionPolicy.reconcile(
                                earlyScreenQuestionText, rawFinal
                            ) == com.myra.assistant.screen.ScreenQuestionReconciliation.MATERIAL_CHANGE
                        ) {
                            voiceLog(
                                "screen_query_reconciled screenQueryId=$screenResponseQueryId " +
                                    "result=cancelled_material_change userTurnId=$screenResponseUserTurnId"
                            )
                            audio?.interrupt(); live?.interrupt()
                            finishScreenResponse("final_transcript_materially_changed")
                            return@turnComplete
                        }
                        val finalDisplay = finalTranscriptDisplay(rawFinal)
                        val semantic = FinalSemanticUserUtterance.from(
                            transcriptSessionId, screenResponseUserTurnId, rawFinal, finalDisplay
                        )
                        commitFinalUserMessage(rawFinal, "TURN_COMPLETE_EARLY_SCREEN_QUERY", semantic.canonicalSemanticText, semantic.displayText)
                        earlyScreenQueryAwaitingFinalTranscript = false
                        input.clear(); commandProbe.clear()
                        voiceLog("screen_query_final_transcript_committed screen_query_id=$screenResponseQueryId userTurnId=$screenResponseUserTurnId")
                        voiceLog("screen_query_reconciled screenQueryId=$screenResponseQueryId result=matched_same_turn userTurnId=$screenResponseUserTurnId")
                        if (!screenResponseHasContent) return@turnComplete
                    }
                    if (!screenResponseHasContent) {
                        voiceLog("screen_response_empty_boundary_ignored screen_query_id=$screenResponseQueryId")
                        return@turnComplete
                    }
                    val current = isScreenResponseContextCurrent()
                    val text = output.toString().trim()
                    if (current && text.isNotBlank() && !screenResponseTextCommitted) {
                        fastVisualTurns.current()?.takeIf { it.userTurnId == screenResponseUserTurnId && it.replyQueuedAt == 0L }?.apply {
                            replyQueuedAt = android.os.SystemClock.elapsedRealtime()
                            voiceLog("replyQueued visualTurnId=$id at=$replyQueuedAt")
                        }
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
                    if (!localAudioSpeaking) {
                        voiceLog(
                            "SCREEN_RESPONSE_DECISION visualTurnId=${fastVisualTurns.current()?.id.orEmpty()} " +
                                "currentTurn=$screenResponseUserTurnId generationId=$screenResponseGenerationId owner=CONTROLLED_SCREEN " +
                                "decision=${if ((fastVisualTurns.current()?.firstAudioAt ?: 0L) > 0L) "PLAY" else "DROP"} " +
                                "reason=generation_complete_no_active_playback"
                        )
                        finishScreenResponse("generation_complete_no_playback")
                    }
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
                voiceTurnIdentities.finalTranscript(activeTurnId, finalUtterance.utteranceId)
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
                if (earlyScreenQueryDispatchedTurnId == activeTurnId && earlyScreenQuestionText.isNotBlank()) {
                    val reconciliation = com.myra.assistant.screen.EarlyScreenQuestionPolicy.reconcile(
                        earlyScreenQuestionText, userText
                    )
                    voiceLog(
                        "screen_query_reconciled userTurnId=$activeTurnId result=$reconciliation " +
                            "visualTurnId=${fastVisualTurns.current()?.id.orEmpty()}"
                    )
                    if (reconciliation == com.myra.assistant.screen.ScreenQuestionReconciliation.MATERIAL_CHANGE) {
                        fastVisualTurns.cancel()
                        live?.interrupt()
                        suppressModelForTurn = true
                        output.clear()
                        resetTurnBuffers("early_screen_query_material_change")
                        waitingForFreshInputAfterCommand = true
                        return@turnComplete
                    }
                }
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
                AccessibilityHelperService.instance?.currentForegroundContext()?.let {
                    brain.observeForegroundApp(it.packageName)
                    voiceLog("foreground_context_propagated turnId=$activeTurnId package=${it.packageName} windowId=${it.windowId} generation=${it.generation}")
                }
                val activityContext = ActivityContextStore.snapshot()
                latestTurnAcceptedAt = android.os.SystemClock.elapsedRealtime()
                latestIntentTimingTurnId = activeTurnId
                voiceLog(
                    "finalTranscriptReady turnId=$activeTurnId at=$latestTurnAcceptedAt " +
                        "authoritativeTurnToTranscriptMs=${if (speechActivityEndedAt > 0L) latestTurnAcceptedAt - speechActivityEndedAt else -1L}"
                )
                latestActionDispatchedAt = 0L
                val turnDecision = UnifiedLyraAgentRuntime.agent.acceptTurn(
                    normalizedFinalUserText, activityContext, visualAwarenessPreferences.enabled, activeTurnId
                )
                latestIntentDecidedAt = android.os.SystemClock.elapsedRealtime()
                voiceLog(
                    "turnIntentResolved turnId=$activeTurnId intent=${turnDecision.intent} at=$latestIntentDecidedAt " +
                        "transcriptToIntentMs=${latestIntentDecidedAt - latestTurnAcceptedAt}"
                )
                val unifiedTask = UnifiedLyraAgentRuntime.agent.currentTask()
                val stagedCapabilities = buildList {
                    if (pendingScrollCandidates.current()?.turnId == activeTurnId) add(ToolCapability.ACCESSIBILITY_SCROLL.name)
                }
                val selectedCapability = when (unifiedTask?.interpretedGoal) {
                    com.myra.assistant.agent.AgentGoalType.SCROLL -> ToolCapability.ACCESSIBILITY_SCROLL.name
                    com.myra.assistant.agent.AgentGoalType.BROWSER_SEARCH,
                    com.myra.assistant.agent.AgentGoalType.WEB_SEARCH -> ToolCapability.BROWSER_SEARCH.name
                    com.myra.assistant.agent.AgentGoalType.TAP -> ToolCapability.ACCESSIBILITY_CLICK.name
                    com.myra.assistant.agent.AgentGoalType.NAVIGATE -> ToolCapability.BACK.name
                    else -> "NONE"
                }
                val discardedCapabilities = stagedCapabilities.filter { it != selectedCapability }
                if (selectedCapability != ToolCapability.ACCESSIBILITY_SCROLL.name) pendingScrollCandidates.discardForTurn(activeTurnId)
                voiceLog(
                    "FINAL_INTENT_CAPABILITY_RESOLUTION turnId=$activeTurnId finalIntent=${turnDecision.intent} " +
                        "selectedCapability=$selectedCapability stagedCapabilities=${stagedCapabilities.joinToString(",")} " +
                        "discardedCapabilities=${discardedCapabilities.joinToString(",")} reason=final_unified_intent_authoritative"
                )
                voiceLog(
                    "agent_turn_owned turnId=$activeTurnId intent=${turnDecision.intent} " +
                        "phoneActions=${turnDecision.authorizesPhoneActions} memoryMutation=${turnDecision.authorizesMemoryMutation} " +
                        "requiresPerception=${turnDecision.requiresPerception} taskId=${unifiedTask?.id}"
                )
                voiceLog(
                    "turn_latency turnId=$activeTurnId speechEndToTurnAcceptedMs=${if (speechActivityEndedAt > 0L) latestTurnAcceptedAt - speechActivityEndedAt else -1L} " +
                        "turnAcceptedToIntentMs=${latestIntentDecidedAt - latestTurnAcceptedAt}"
                )
                if (unifiedTask != null && turnDecision.intent in setOf(TurnIntent.ACTION_REQUEST, TurnIntent.MULTI_STEP_GOAL)) {
                    voiceLog(
                        "AGENT_TASK_CREATED taskId=${unifiedTask.id} turnId=$activeTurnId goal=${unifiedTask.interpretedGoal} " +
                            "foregroundPackage=${activityContext?.packageName} screenGeneration=${activityContext?.generation ?: 0L}"
                    )
                    voiceLog(
                        "agent_task_created taskId=${unifiedTask.id} goal=${unifiedTask.interpretedGoal} package=${activityContext?.packageName} " +
                            "planSteps=${unifiedTask.plan.size} confidence=${unifiedTask.confidence}"
                    )
                    voiceLog("agent_plan_created taskId=${unifiedTask.id} steps=${unifiedTask.plan.joinToString(",") { it.id }}")
                }
                if (turnDecision.intent in setOf(TurnIntent.CONVERSATION, TurnIntent.QUESTION)) {
                    // A complete conversational turn hard-locks all phone executors. Partial
                    // keyword guesses are discarded and Gemini retains the sole response.
                    probableActionTurn = false
                    suppressModelForTurn = false
                    voiceLog("agent_phone_tools_locked turnId=$activeTurnId reason=${turnDecision.intent}")
                    pendingScrollCandidates.discardForTurn(activeTurnId)
                }
                if (turnDecision.intent == TurnIntent.FOLLOW_UP) {
                    handleUnifiedActionFollowUp()
                    resetTurnBuffers("unified_action_follow_up")
                    waitingForFreshInputAfterCommand = true
                    return@turnComplete
                }
                if (turnDecision.intent in setOf(TurnIntent.ACTION_REQUEST, TurnIntent.MULTI_STEP_GOAL) &&
                    unifiedTask?.interpretedGoal == com.myra.assistant.agent.AgentGoalType.SCROLL
                ) {
                    val runtimeTask = GeneralAgentRuntimeStore.runtime.activeTask()
                    val directionName = runtimeTask?.intent?.parameters?.get("direction")
                        ?: lastScrollDirection.name
                    val direction = runCatching { AppCommand.ScrollDirection.valueOf(directionName) }
                        .getOrDefault(lastScrollDirection)
                    handleScrollProposal(
                        AppCommand.ScrollYouTube(direction), "final_unified_turn",
                        ScrollProposalAuthorization.FINAL_AUTHORIZED,
                        requestedTaskId = runtimeTask?.id
                    )
                    resetTurnBuffers("unified_runtime_scroll")
                    waitingForFreshInputAfterCommand = true
                    return@turnComplete
                }
                if (turnDecision.intent in setOf(TurnIntent.ACTION_REQUEST, TurnIntent.MULTI_STEP_GOAL) &&
                    executeUnifiedBrowserSearch(normalizedFinalUserText)
                ) {
                    resetTurnBuffers("unified_browser_search")
                    waitingForFreshInputAfterCommand = true
                    return@turnComplete
                }
                val screenMode = ScreenModeCommandParser.parse(userText)
                    ?: ScreenModeCommandParser.parse(normalizedFinalUserText)
                if (turnDecision.authorizesPhoneActions && screenMode != null) {
                    executeScreenModeCommand(screenMode)
                    resetTurnBuffers("screen_mode_command")
                    waitingForFreshInputAfterCommand = true
                    return@turnComplete
                }
                if (turnDecision.authorizesPhoneActions && executeGenericSemanticRuntime(unifiedTask)) {
                    resetTurnBuffers("general_semantic_runtime")
                    waitingForFreshInputAfterCommand = true
                    return@turnComplete
                }
                // Keep the original transcript for local semantic commands. The display/brain
                // normalization can transliterate Devanagari (for example, "कमेंट" into an
                // unrecognisable spelling), but accessibility actions must be decided first.
                val youtubeSemantic = YouTubeSemanticCommandParser.parse(userText)
                    ?: YouTubeSemanticCommandParser.parse(normalizedFinalUserText)
                if (turnDecision.authorizesPhoneActions && youtubeSemantic != null && executeYouTubeSemanticAction(youtubeSemantic)) {
                    resetTurnBuffers("youtube_semantic_action")
                    waitingForFreshInputAfterCommand = true
                    return@turnComplete
                }
                val fastVisualRequest = FastVisualRequestClassifier.classify(userText)
                    ?: FastVisualRequestClassifier.classify(normalizedFinalUserText)
                    ?: ScreenVisionIntentParser.parse(normalizedFinalUserText)?.let {
                        FastVisualRequest(
                            if (it == com.myra.assistant.screen.ScreenVisionIntent.CONTROL_TARGET) FastVisualKind.ACTION else FastVisualKind.QUESTION,
                            it.name.lowercase(Locale.ROOT)
                        )
                    }
                if (fastVisualRequest != null &&
                    (turnDecision.intent == TurnIntent.SCREEN_QUESTION || turnDecision.authorizesPhoneActions)) {
                    if (turnDecision.intent == TurnIntent.SCREEN_QUESTION && ordinaryModelAudioGate.isSpeechActive()) {
                        armScreenQuestion(userText, activeTurnId, "FINAL_SCREEN_QUERY_WAITING_FOR_SPEECH_END", true)
                        suppressModelForTurn = true
                        output.clear()
                        return@turnComplete
                    }
                    if (ScreenQueryDispatchPolicy.shouldDispatch(
                            screenResponseActive, earlyScreenQueryDispatchedTurnId, activeTurnId
                        )) {
                        beginFreshScreenQuery(userText, activeTurnId, fastVisualRequest)
                    }
                    resetTurnBuffers("fast_visual_turn")
                    waitingForFreshInputAfterCommand = true
                    return@turnComplete
                }
                if (turnDecision.authorizesPhoneActions && executeUnifiedReferenceIfApplicable(userText)) {
                    resetTurnBuffers("unified_agent_reference")
                    waitingForFreshInputAfterCommand = true
                    return@turnComplete
                }
                val brainDecision = if (turnDecision.authorizesPhoneActions) brain.interpret(normalizedFinalUserText)
                    else BrainDecision.PassThrough
                voiceLog(
                    "brain_decision turnId=$activeTurnId intent=${LyraBrainCoordinator.classify(normalizedFinalUserText)} " +
                        "decision=${brainDecision.javaClass.simpleName} state=${brain.snapshot()}"
                )
                val readingCommand = ReadingIntentParser.parse(normalizedFinalUserText)
                if (turnDecision.authorizesPhoneActions && readingCommand != null && handleReadingCommand(readingCommand, activeTurnId)) {
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
                        cancelSpeechForNewAction()
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
                if (screenIntent != null && turnDecision.intent == TurnIntent.SCREEN_QUESTION) {
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
                    if (turnDecision.authorizesPhoneActions && parsed != null) {
                        executeCommand(parsed)
                    } else if (turnDecision.authorizesPhoneActions &&
                        (probableActionTurn || CommandParser.isProbableDeviceAction(userText))) {
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
                    // The interruption is also the beginning of the replacement user
                    // utterance. Allocate its identity now; waiting for ASR recreated
                    // the old turnId=0 / new transcript-turn mismatch.
                    beginOrdinarySpeechActivity(latestObservedModelGenerationId, "local_vad_after_screen_replacement")
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
                if (speaking && screenResponseActive) {
                    fastVisualTurns.current()?.takeIf { it.userTurnId == screenResponseUserTurnId && it.firstPlaybackAt == 0L }?.apply {
                        firstPlaybackAt = android.os.SystemClock.elapsedRealtime()
                        voiceLog("firstPlayback visualTurnId=$id at=$firstPlaybackAt speechEndToFirstPlaybackMs=${if (speechEndedAt > 0L) firstPlaybackAt - speechEndedAt else -1L}")
                    }
                }
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
        val pendingSearch = BrowserSearchRequestParser.parse(guardedText)
        if (pendingSearch != null && action in setOf("YOUTUBE_SEARCH", "PLAY_YOUTUBE", "OPEN_APP")) {
            // Gemini's streaming tool proposal is not a search destination owner. The
            // final transcript is resolved once by executeUnifiedBrowserSearch().
            voiceLog(
                "SEARCH_EXECUTOR_ENTRY class=MyraVoiceService method=handleSemanticToolCall " +
                    "turnId=$activeTurnId finalTranscript=false query=${pendingSearch.query.take(120)} " +
                    "destination=CANDIDATE foregroundPackage=${AccessibilityHelperService.instance?.currentForegroundContext()?.packageName} " +
                    "decision=REJECT_PRE_FINAL"
            )
            suppressModelForTurn = true
            output.clear()
            live?.sendToolResponse(
                id, functionName, false,
                "Search execution is owned by the final unified search task"
            )
            return
        }
        if (action == "YOUTUBE_SEARCH" && !SearchExecutionPolicy.mayExecute(authoritativeFinalTranscript = false)) {
            // Search destination is authorized only from the complete final transcript.
            // A speculative Live tool call may arrive while ASR is still partial and must
            // never choose YouTube before SearchDestinationResolver sees current context.
            voiceLog(
                "search_execution_failed turnId=$activeTurnId reason=model_tool_before_final_authorization " +
                    "candidate=YOUTUBE_SEARCH queryLength=${query.length}"
            )
            live?.sendToolResponse(
                id, functionName, false,
                "Search waits for the final authoritative transcript and contextual destination resolution"
            )
            return
        }
        val command: AppCommand? = when (action) {
            "OPEN_APP" -> target.takeIf { it.length in 2..40 }?.let(AppCommand::OpenApp)
            "CLOSE_APP" -> AppCommand.CloseCurrentApp(target.ifBlank { null })
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
        if (command is AppCommand.ScrollYouTube) {
            handleScrollProposal(command, "gemini_phone_tool", ScrollProposalAuthorization.PRE_FINAL)
            live?.sendToolResponse(
                id, functionName, true,
                "Scroll proposal staged; final Android turn authorization is pending"
            )
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

    @Suppress("UNREACHABLE_CODE")
    private fun handleScreenActionTool(id: String, args: org.json.JSONObject) {
        val intentText = lastUserIntentText.ifBlank { input.toString().trim() }
        screenActionRegistry.cancel()?.let {
            voiceLog("SCREEN_ACTION_CANCELLED actionId=${it.actionId} turnId=${it.turnId} reason=new_explicit_screen_command")
        }
        if (ScreenVisionIntentParser.parse(intentText) == null &&
            UnifiedLyraAgentRuntime.agent.currentTask()?.interpretedGoal != com.myra.assistant.agent.AgentGoalType.TAP &&
            fastVisualTurns.current()?.kind != FastVisualKind.ACTION
        ) {
            live?.sendToolResponse(id, "perform_screen_action", false, "No explicit visible-screen action was requested")
            return
        }
        // Model screen-tool output is only a proposal. Physical action is owned by
        // the final unified transcript and its GeneralAgentRuntime task.
        voiceLog(
            "SEMANTIC_TARGET_CANDIDATE_STAGED turnId=$activeTurnId source=perform_screen_action " +
                "target=${args.optString("target_text").take(100)} position=${args.optString("position")} " +
                "ordinal=${args.optInt("ordinal", 0)} decision=WAIT_FOR_FINAL"
        )
        live?.sendToolResponse(
            id, "perform_screen_action", true,
            "Semantic target proposal staged; final Android turn authorization is pending"
        )
        return
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
        val foreground = accessibility.currentForegroundContext()
        val actionScope = com.myra.assistant.screen.ForegroundActionPolicy.scope(foreground)
        if (actionScope == null) {
            live?.sendToolResponse(id, "perform_screen_action", false, "Current Accessibility window is unavailable")
            return
        }
        val beforeAccessibility = accessibility.visibleScreenSignature()
        fastVisualTurns.current()?.let {
            it.actionResolvedAt = android.os.SystemClock.elapsedRealtime()
            voiceLog("visual_action_resolved visualTurnId=${it.id} target=${target.orEmpty().take(80)} position=${position.orEmpty()} ordinal=${ordinal ?: 0}")
        }
        val semanticHint = fastVisualTurns.current()?.semanticHint.orEmpty().lowercase(Locale.ROOT)
        val direct = accessibility.resolveAndTapVisibleTarget(target, position, ordinal, actionScope) { candidate, _ ->
            when {
                semanticHint.contains("like") -> candidate.role == "like_control"
                semanticHint.contains("subscribe") -> candidate.role == "subscribe_control" &&
                    !candidate.label.lowercase(Locale.ROOT).contains("subscribed")
                semanticHint.contains("comment") -> candidate.role == "comments_control"
                else -> true
            }
        }
        if (direct.accepted) {
            fastVisualTurns.current()?.let {
                it.actionExecutedAt = android.os.SystemClock.elapsedRealtime()
                voiceLog(
                    "visual_action_executed visualTurnId=${it.id} accepted=true " +
                        "responseToActionMs=${if (it.firstModelResponseAt > 0L) it.actionExecutedAt - it.firstModelResponseAt else -1L} " +
                        "speechEndToActionMs=${if (it.speechEndedAt > 0L) it.actionExecutedAt - it.speechEndedAt else -1L}"
                )
            }
            voiceLog(
                "agent_tool_selected tool=accessibility_click package=${actionScope.expectedPackage} " +
                    "windowGeneration=${actionScope.expectedGeneration} targetResolution=${direct.resolution}"
            )
            mainHandler.postDelayed({
                val stillOwned = com.myra.assistant.screen.ForegroundActionPolicy.canExecute(
                    actionScope, accessibility.currentForegroundContext()
                )
                val changed = stillOwned && beforeAccessibility.isNotBlank() &&
                    accessibility.visibleScreenSignature() != beforeAccessibility
                fastVisualTurns.current()?.let {
                    it.verificationAt = android.os.SystemClock.elapsedRealtime()
                    voiceLog("visual_verification_complete visualTurnId=${it.id} verified=$changed totalVisualTurnMs=${it.verificationAt - it.startedAt}")
                    fastVisualTurns.finish(it.id)
                }
                voiceLog("agent_verification tool=accessibility_click accepted=true verified=$changed")
                live?.sendToolResponse(
                    id, "perform_screen_action", changed,
                    if (changed) "Accessibility action verified" else "Action was accepted but the expected screen change was not verified"
                )
            }, 350L)
            return
        }
        // Normal visual actions never request MediaProjection. The model already
        // received a fresh Accessibility screenshot when visual fallback was used.
        live?.sendToolResponse(
            id, "perform_screen_action", false,
            if (direct.resolution == "ambiguous") "Visible target is ambiguous; ask the user to choose"
            else "No current Accessibility target matched; ask a short clarification"
        )
        return
    }

    private fun beginFreshScreenQuery(
        question: String,
        userTurnId: Long,
        visualRequest: FastVisualRequest = FastVisualRequestClassifier.classify(question)
            ?: FastVisualRequest(FastVisualKind.QUESTION, "screen_question")
    ) {
        screenQuestionDetectedAt = android.os.SystemClock.elapsedRealtime()
        val identity = voiceTurnIdentities.current()?.takeIf { it.userTurnId == userTurnId }
        val boundSpeechTurnId = identity?.transcriptTurnId ?: speechTimingTurnId
        val boundSpeechEndAt = identity?.speechEndAt?.takeIf { it > 0L } ?: speechActivityEndedAt
        val speechTiming = ScreenQueryTimingPolicy.bind(userTurnId, boundSpeechTurnId, boundSpeechEndAt)
        screenQuerySpeechTurnConsistency = speechTiming.consistent
        screenResponseSpeechEndedAt = speechTiming.speechEndAt
        voiceLog(
            "screen_query_timing_bound userTurnId=$userTurnId speechTimingTurnId=$boundSpeechTurnId " +
                "speechStartAt=${identity?.speechStartAt ?: speechActivityStartedAt} speechEndAt=$screenResponseSpeechEndedAt " +
                "transcriptTurnId=${identity?.transcriptTurnId ?: 0L} finalTranscriptId=${identity?.finalTranscriptId.orEmpty()} " +
                "intentDetectedAt=$screenQuestionDetectedAt screenQuerySpeechTurnConsistency=$screenQuerySpeechTurnConsistency"
        )
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        output.clear()
        val foreground = AccessibilityHelperService.instance?.currentForegroundContext()
        val visualTurn = foreground?.let {
            fastVisualTurns.begin(userTurnId, visualRequest, it.packageName, it.windowId, it.generation,
                screenResponseSpeechEndedAt, screenQuestionDetectedAt)
        }
        visualTurn?.apply {
            authoritativeTurnCompleteAt = screenResponseSpeechEndedAt
            finalTranscriptAt = latestTurnAcceptedAt.takeIf { latestIntentTimingTurnId == userTurnId } ?: 0L
            intentResolvedAt = latestIntentDecidedAt.takeIf { latestIntentTimingTurnId == userTurnId } ?: screenQuestionDetectedAt
        }
        voiceLog(
            "visual_turn_started visualTurnId=${visualTurn?.id.orEmpty()} userTurnId=$userTurnId " +
                "kind=${visualRequest.kind} package=${foreground?.packageName.orEmpty()} " +
                "windowId=${foreground?.windowId ?: -1} generation=${foreground?.generation ?: -1}"
        )
        voiceLog(
            "visualTurnAccepted visualTurnId=${visualTurn?.id.orEmpty()} at=$screenQuestionDetectedAt " +
                "intentToVisualTurnMs=${if (latestIntentDecidedAt > 0L) screenQuestionDetectedAt - latestIntentDecidedAt else -1L}"
        )
        // Preserve an active media-speech candidate when LYRA is already silent;
        // interrupt/reset is only needed for a genuine barge-in on LYRA playback.
        if (localAudioSpeaking) audio?.interrupt()
        if (visualRequest.kind == FastVisualKind.QUESTION && tryInstantAccessibilityAnswer(question, userTurnId)) {
            visualTurn?.let { fastVisualTurns.finish(it.id) }
            return
        }
        if (visualAwarenessPreferences.enabled && beginAccessibilityScreenQuery(question, userTurnId, visualRequest)) return
        if (!visualAwarenessPreferences.enabled) {
            voiceLog("screen_query_terminal state=REJECTED_VISUAL_AWARENESS_OFF userTurnId=$userTurnId")
            visualTurn?.let {
                voiceLog("TOTAL_VISUAL_TURN visualTurnId=${it.id} route=EYE_OFF_LOCAL totalVisualTurnMs=${android.os.SystemClock.elapsedRealtime() - it.startedAt}")
                fastVisualTurns.finish(it.id)
            }
            speakScreenUnavailable("Visual awareness off hai. Eye button on karo.")
            return
        }
        // Android 10 and older do not expose AccessibilityService.takeScreenshot.
        // A user-started continuous projection is the explicit legacy fallback.
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

    private fun tryInstantAccessibilityAnswer(question: String, userTurnId: Long): Boolean {
        val queryType = ScreenVisionIntentParser.parseInstantQuery(question) ?: return false
        if (queryType != InstantScreenQuery.CURRENT_APP) return false
        val context = ActivityContextStore.snapshot() ?: return false
        val now = android.os.SystemClock.elapsedRealtime()
        if ((now - context.timestamp).coerceAtLeast(0L) > 1_500L) return false
        val safe = context.visibleElements.asSequence().map { it.label }
            .filter { it.length >= 3 && ScreenPrivacyPolicy.sensitiveCategory(it) == null }
            .distinct().take(3).toList()
        val answer = when (queryType) {
            InstantScreenQuery.CURRENT_APP -> context.appLabel?.let { "$it open hai." }
                ?: "${context.packageName.substringAfterLast('.')} open hai."
            InstantScreenQuery.OVERVIEW -> when {
                safe.isNotEmpty() -> "${context.appLabel ?: context.packageName.substringAfterLast('.')} open hai. Screen par ${safe.joinToString(", ")} dikh raha hai."
                else -> null
            }
        } ?: return false
        voiceLog("TOTAL_SCREEN_RESPONSE screenQueryId=a11y-cache-$userTurnId route=ACCESSIBILITY_CONTEXT total_ms=0 screenshotUsed=false")
        emitState(answer)
        queueLocalSpeech(answer, allowUntranscribedAudio = true)
        return true
    }

    private fun beginAccessibilityScreenQuery(
        question: String,
        userTurnId: Long,
        visualRequest: FastVisualRequest = FastVisualRequest(FastVisualKind.QUESTION, "screen_question")
    ): Boolean {
        val accessibility = AccessibilityHelperService.instance ?: return false
        val foreground = accessibility.currentForegroundContext() ?: return false
        val requestedAt = android.os.SystemClock.elapsedRealtime()
        val queryId = "a11y-$userTurnId-${requestedAt.toString(16)}"
        val visualTurnId = fastVisualTurns.current()?.takeIf { it.userTurnId == userTurnId }?.id
        fastVisualTurns.current()?.takeIf { it.id == visualTurnId }?.frameRequestedAt = requestedAt
        voiceLog("visualFrameRequested visualTurnId=${visualTurnId.orEmpty()} screenQueryId=$queryId at=$requestedAt")
        if (visualTurnId == null) return false
        val acquisitionGate = VisualAcquisitionGate(visualTurnId, requestedAt)
        val outerTimeout = visualDeadlineExecutor.schedule({
            val now = android.os.SystemClock.elapsedRealtime()
            if (!acquisitionGate.tryTimeout(now)) return@schedule
            voiceLog(
                "visual_frame_outer_timeout visualTurnId=$visualTurnId screenQueryId=$queryId " +
                    "elapsedMs=${now - requestedAt} timeoutMs=${VisualScreenshotTimeoutPolicy.OUTER_ACQUISITION_TIMEOUT_MS}"
            )
            // Deadline fallback must not queue behind the very image worker it is
            // timing out. This executor owns only deadlines and can terminate the turn
            // even if frame delivery is blocked.
            if (fastVisualTurns.owns(visualTurnId)) {
                completeScreenQuestionFromSemanticScene(question, userTurnId, visualTurnId, queryId, foreground)
            }
        }, VisualScreenshotTimeoutPolicy.OUTER_ACQUISITION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val accepted = accessibility.requestFreshVisualScreenshot(
            ACCESSIBILITY_VISUAL_CACHE_MAX_AGE_MS,
            requestToken = queryId,
            isCurrentRequest = {
                acquisitionGate.mayDispatch(fastVisualTurns.current()?.id, android.os.SystemClock.elapsedRealtime())
            }
        ) { result ->
            val scheduledAt = android.os.SystemClock.elapsedRealtime()
            if (!acquisitionGate.onPlatformCallback(fastVisualTurns.current()?.id, scheduledAt)) {
                voiceLog(
                    "visualFrameDeliveryScheduled visualTurnId=$visualTurnId screenQueryId=$queryId " +
                        "accepted=false reason=callback_after_outer_deadline_or_replacement taskAgeMs=${scheduledAt - requestedAt}"
                )
                return@requestFreshVisualScreenshot
            }
            val queueDepth = visualFrameDeliveryExecutor.queue.size
            voiceLog(
                "visualFrameDeliveryScheduled visualTurnId=$visualTurnId screenQueryId=$queryId " +
                    "timestamp=$scheduledAt executorName=lyra-current-visual-delivery threadName=${Thread.currentThread().name} " +
                    "queueDepth=$queueDepth taskAgeMs=${scheduledAt - requestedAt}"
            )
            visualFrameDeliveryExecutor.execute {
                val deliveryStartedAt = android.os.SystemClock.elapsedRealtime()
                voiceLog(
                    "visualFrameDeliveryStarted visualTurnId=$visualTurnId screenQueryId=$queryId " +
                        "timestamp=$deliveryStartedAt executorName=lyra-current-visual-delivery threadName=${Thread.currentThread().name} " +
                        "queueDepth=${visualFrameDeliveryExecutor.queue.size} taskAgeMs=${deliveryStartedAt - requestedAt} lockWaitMs=0"
                )
                // The outer deadline owns the complete operation through usable-frame
                // delivery. Android callback success alone must not complete this gate.
                if (!acquisitionGate.tryComplete(fastVisualTurns.current()?.id, deliveryStartedAt)) {
                    result.getOrNull()?.screenshot?.let {
                        voiceLog(
                            "visualFrameDelivered visualTurnId=$visualTurnId screenQueryId=$queryId accepted=false " +
                                "reason=outer_deadline_or_replaced cacheWarmOnly=true taskAgeMs=${deliveryStartedAt - requestedAt}"
                        )
                    }
                    voiceLog(
                        "screen_query_result_dropped_stale screen_query_id=$queryId visualTurnId=$visualTurnId " +
                            "reason=outer_deadline_or_replaced"
                    )
                    return@execute
                }
                outerTimeout.cancel(false)
                if (visualTurnId == null || !fastVisualTurns.owns(visualTurnId)) {
                    voiceLog("screen_query_result_dropped_stale screen_query_id=$queryId visualTurnId=${visualTurnId.orEmpty()} reason=visual_turn_replaced")
                    return@execute
                }
                val selection = result.getOrNull()
                if (selection == null) {
                    val reason = result.exceptionOrNull()?.message ?: "accessibility_screenshot_failed"
                    voiceLog("screenshot_failure_reason screenQueryId=$queryId reason=$reason")
                    voiceLog("agent_observation package=${foreground.packageName} screenshotUsed=false reason=$reason")
                    completeScreenQuestionFromSemanticScene(
                        question, userTurnId, visualTurnId, queryId, foreground
                    )
                    return@execute
                }
                val screenshot = selection.screenshot
                val current = accessibility.currentForegroundContext()
                if (current == null || current.packageName != screenshot.packageName ||
                    current.windowId != screenshot.windowId || current.generation != screenshot.generation
                ) {
                    voiceLog("screen_query_result_dropped_stale screen_query_id=$queryId reason=accessibility_context_changed")
                    fastVisualTurns.finish(visualTurnId)
                    return@execute
                }
                val frameReadyAt = android.os.SystemClock.elapsedRealtime()
                fastVisualTurns.current()?.takeIf { it.id == visualTurnId }?.frameReadyAt = frameReadyAt
                voiceLog(
                    "visual_frame_ready visualTurnId=$visualTurnId screenQueryId=$queryId " +
                        "visualFrameSource=${selection.source} selectionReason=${if (selection.source == com.myra.assistant.screen.VisualFrameSource.ACCESSIBILITY_CACHE) "fresh_matching_cache" else "cache_stale_or_changed"} " +
                        "frameAgeMs=${(frameReadyAt - screenshot.capturedAt).coerceAtLeast(0L)} " +
                        "visualFrameAcquisitionMs=${(frameReadyAt - requestedAt).coerceAtLeast(0L)} " +
                        "speechEndToFrameReadyMs=${if (screenResponseSpeechEndedAt > 0L) frameReadyAt - screenResponseSpeechEndedAt else -1L}"
                )
                voiceLog(
                    "visualFrameAvailable visualTurnId=$visualTurnId screenQueryId=$queryId " +
                        "at=$frameReadyAt visualFrameSource=${selection.source}"
                )
                voiceLog(
                    "visualFrameDelivered visualTurnId=$visualTurnId screenQueryId=$queryId accepted=true " +
                        "timestamp=$frameReadyAt executorName=lyra-current-visual-delivery threadName=${Thread.currentThread().name} " +
                        "queueDepth=${visualFrameDeliveryExecutor.queue.size} taskAgeMs=${frameReadyAt - requestedAt}"
                )
                // Reuse the already-published semantic scene. Rewalking a large
                // Accessibility tree here previously delayed the visual model request.
                val elements = ActivityContextStore.snapshot()?.takeIf {
                    it.packageName == current.packageName && it.windowId == current.windowId &&
                        it.generation == current.generation
                }?.visibleElements?.take(60)?.map {
                    VisibleScreenElement(
                        it.label,
                        android.graphics.Rect(it.left, it.top, it.right, it.bottom),
                        it.actionable,
                        it.role.name
                    )
                }.orEmpty()
                val privacyResult = ScreenFramePrivacyFilter.apply(
                    screenshot.bytes, elements, screenshot.width, screenshot.height,
                    screenVisionPreferences.sensitiveContentProtection
                )
                if (privacyResult is ScreenPrivacyResult.Blocked) {
                    voiceLog("screen_query_terminal screenQueryId=$queryId state=REJECTED_PRIVACY source=ACCESSIBILITY_SCREENSHOT")
                    fastVisualTurns.finish(visualTurnId)
                    speakScreenPrivacyBlocked()
                    return@execute
                }
                val allowed = privacyResult as ScreenPrivacyResult.Allowed
                screenResponseActive = true
                screenResponseHasContent = false
                screenResponseStartedLogged = false
                screenResponseGenerationComplete = false
                screenResponseTextCommitted = false
                screenResponseUserTurnId = userTurnId
                screenResponseAfterGenerationId = latestObservedModelGenerationId
                screenResponseGenerationId = 0L
                val sessionId = "accessibility:${current.packageName}:${current.generation}"
                screenResponseBinding = ScreenResponseBinding(userTurnId, queryId, sessionId, latestObservedModelGenerationId)
                screenResponseSessionId = sessionId
                screenResponseQueryId = queryId
                screenResponseAccessibilityPackage = current.packageName
                screenResponseAccessibilityGeneration = current.generation
                screenFreshFrameCapturedAt = screenshot.capturedAt
                screenFrameSentAt = android.os.SystemClock.elapsedRealtime()
                fastVisualTurns.current()?.takeIf { it.id == visualTurnId }?.modelRequestAt = screenFrameSentAt
                val ui = elements.filter { ScreenPrivacyPolicy.sensitiveCategory(it.label) == null }
                    .joinToString("\n") { "${it.label} [${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}]" }
                    .take(4_000)
                voiceLog(
                    "agent_observation package=${current.packageName} windowGeneration=${current.generation} " +
                        "semanticElements=${ActivityContextStore.snapshot()?.visibleElements?.size ?: 0} screenshotUsed=true"
                )
                voiceLog(
                    "VISION_REQUEST_STARTED screenQueryId=$queryId source=ACCESSIBILITY_SCREENSHOT " +
                        "captureMs=${screenFrameSentAt - requestedAt} bytes=${allowed.bytes.size}"
                )
                voiceLog(
                    "visual_model_request_sent visualTurnId=$visualTurnId screenQueryId=$queryId " +
                        "frameReadyToModelRequestMs=${(screenFrameSentAt - frameReadyAt).coerceAtLeast(0L)}"
                )
                voiceLog(
                    "visual_model_payload visualTurnId=$visualTurnId imageEncodedBytes=${allowed.bytes.size} " +
                        "imageDimensions=${screenshot.width}x${screenshot.height} semanticContextChars=${ui.length} " +
                        "semanticElementCount=${elements.size} requestPayloadBytes=${allowed.bytes.size + ui.toByteArray().size + question.toByteArray().size} " +
                        "networkSendAt=$screenFrameSentAt"
                )
                voiceLog("visualModelRequestSent visualTurnId=$visualTurnId screenQueryId=$queryId at=$screenFrameSentAt")
                voiceLog("ttsRequestSent visualTurnId=$visualTurnId screenQueryId=$queryId at=$screenFrameSentAt owner=CONTROLLED_SCREEN")
                val visualInstruction = if (visualRequest.kind == FastVisualKind.ACTION) {
                    "This is a visual action. Identify exactly one safe current-screen target. " +
                        "Call perform_screen_action with its semantic label or position. Do not answer conversationally or claim success."
                } else {
                    "Answer the user's current-screen question directly in one or two complete sentences."
                }
                live?.sendImage(
                    allowed.bytes, "image/jpeg",
                    "$question\nUse only this fresh Accessibility screenshot and current safe UI elements. " +
                        "Do not infer hidden content. $visualInstruction\n$ui"
                )
            }
        }
        if (accepted) {
            voiceLog("screen_query_created screen_query_id=$queryId source=ACCESSIBILITY_SCREENSHOT userTurnId=$userTurnId")
        } else {
            outerTimeout.cancel(false)
        }
        return accepted
    }

    private fun completeScreenQuestionFromSemanticScene(
        question: String,
        userTurnId: Long,
        visualTurnId: String,
        queryId: String,
        expected: com.myra.assistant.screen.ForegroundAppContext
    ) {
        val scene = ActivityContextStore.snapshot()?.takeIf {
            SemanticScreenFallbackPolicy.mayAnswer(
                expected.packageName, expected.windowId, expected.generation,
                it.packageName, it.windowId, it.generation, it.visibleElements.size,
                android.os.SystemClock.elapsedRealtime() - it.timestamp
            )
        }
        val labels = scene?.visibleElements.orEmpty().asSequence()
            .map { it.label.trim() }
            .filter { it.length >= 3 && ScreenPrivacyPolicy.sensitiveCategory(it) == null }
            .distinct().take(4).toList()
        if (scene == null || labels.isEmpty()) {
            fastVisualTurns.finish(visualTurnId)
            voiceLog("screen_query_terminal screenQueryId=$queryId state=CAPTURE_FAILED visualSource=NONE")
            speakScreenUnavailable("Current screen image nahi mili.")
            return
        }
        val app = scene.appLabel ?: scene.packageName.substringAfterLast('.')
        val answer = "$app open hai. Screen par ${labels.joinToString(", ")} dikh raha hai."
        val now = android.os.SystemClock.elapsedRealtime()
        voiceLog(
            "visualFrameAvailable visualTurnId=$visualTurnId screenQueryId=$queryId at=$now " +
                "visualFrameSource=SEMANTIC_SCREEN semanticElements=${scene.visibleElements.size}"
        )
        voiceLog(
            "TOTAL_VISUAL_TURN visualTurnId=$visualTurnId route=SEMANTIC_SCREEN " +
                "visualFrameAcquisitionMs=${now - (fastVisualTurns.current()?.frameRequestedAt ?: now)}"
        )
        fastVisualTurns.finish(visualTurnId)
        emitState(answer)
        queueLocalSpeech(answer, allowUntranscribedAudio = true)
    }

    private fun isScreenResponseContextCurrent(): Boolean {
        if (!screenResponseSessionId.startsWith("accessibility:")) {
            return ScreenCaptureService.session.isCurrent(screenResponseSessionId)
        }
        val current = AccessibilityHelperService.instance?.currentForegroundContext() ?: return false
        return current.packageName == screenResponseAccessibilityPackage &&
            current.generation == screenResponseAccessibilityGeneration
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

    private fun armScreenQuestion(
        question: String,
        userTurnId: Long,
        source: String,
        finalTranscriptCommitted: Boolean = false
    ) {
        if (question.isBlank() || userTurnId == 0L) return
        armedScreenQuestion = question
        armedScreenQuestionTurnId = userTurnId
        armedScreenQuestionDetectedAt = android.os.SystemClock.elapsedRealtime()
        armedScreenQuestionFinalCommitted = finalTranscriptCommitted
        voiceLog(
            "screen_query_intent_detected_at=$armedScreenQuestionDetectedAt userTurnId=$userTurnId source=$source " +
                "speechEndAt=$speechActivityEndedAt finalTranscriptAt=0 stableFinalBubbleCommitted=false"
        )
        // ASR chunks and local VAD are independent streams. The stable read-only screen
        // question often arrives after VAD has already ended, so it must not wait for a
        // second speech edge or Gemini's delayed final transcript.
        if (com.myra.assistant.screen.EarlyScreenQuestionPolicy.mayAuthorizeAtSpeechEnd(
                question, ordinaryModelAudioGate.isSpeechActive()
            ) && speechActivityEndedAt > 0L
        ) {
            mainHandler.postDelayed(
                { dispatchArmedScreenQuestionAtSpeechEnd() },
                com.myra.assistant.screen.EarlyScreenQuestionPolicy.STABILIZATION_MS
            )
        }
    }

    private fun dispatchArmedScreenQuestionAtSpeechEnd() {
        val question = armedScreenQuestion.takeIf { it.isNotBlank() } ?: return
        val turnId = armedScreenQuestionTurnId.takeIf { it != 0L } ?: return
        if (screenResponseActive) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!com.myra.assistant.screen.ArmedScreenQuestionPolicy.mayDispatchForIdentity(
                turnId, voiceTurnIdentities.current()?.userTurnId
            )) {
            voiceLog(
                "screen_query_armed_cancelled userTurnId=$turnId reason=replaced_voice_identity " +
                    "ageMs=${(now - armedScreenQuestionDetectedAt).coerceAtLeast(0L)}"
            )
            armedScreenQuestion = ""
            armedScreenQuestionTurnId = 0L
            armedScreenQuestionDetectedAt = 0L
            armedScreenQuestionFinalCommitted = false
            return
        }
        val stabilizationRemaining = com.myra.assistant.screen.EarlyScreenQuestionPolicy.STABILIZATION_MS -
            (now - armedScreenQuestionDetectedAt)
        if (stabilizationRemaining > 0L) {
            mainHandler.postDelayed({ dispatchArmedScreenQuestionAtSpeechEnd() }, stabilizationRemaining)
            return
        }
        voiceLog(
            "screen_query_early_dispatch userTurnId=$turnId speech_end_at=$speechActivityEndedAt " +
                "screen_query_intent_detected_at=$armedScreenQuestionDetectedAt speechEndToIntentMs=${(armedScreenQuestionDetectedAt - speechActivityEndedAt).coerceAtLeast(0L)} " +
                "intentToDispatchMs=${(now - armedScreenQuestionDetectedAt).coerceAtLeast(0L)}"
        )
        earlyScreenQueryAwaitingFinalTranscript = !armedScreenQuestionFinalCommitted
        earlyScreenQuestionText = question
        earlyScreenQueryDispatchedTurnId = turnId
        beginFreshScreenQuery(question, turnId)
        armedScreenQuestion = ""
        armedScreenQuestionTurnId = 0L
        armedScreenQuestionDetectedAt = 0L
        armedScreenQuestionFinalCommitted = false
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
        screenResponseAccessibilityPackage = ""
        screenResponseAccessibilityGeneration = 0L
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
        fastVisualTurns.current()?.takeIf { it.userTurnId == screenResponseUserTurnId }?.let {
            val now = android.os.SystemClock.elapsedRealtime()
            voiceLog(
                "TOTAL_VISUAL_TURN visualTurnId=${it.id} reason=$reason " +
                    "speechEndToAuthoritativeTurnMs=${if (it.speechEndedAt > 0L && it.authoritativeTurnCompleteAt > 0L) it.authoritativeTurnCompleteAt - it.speechEndedAt else -1L} " +
                    "authoritativeTurnToTranscriptMs=${if (it.authoritativeTurnCompleteAt > 0L && it.finalTranscriptAt > 0L) it.finalTranscriptAt - it.authoritativeTurnCompleteAt else -1L} " +
                    "transcriptToIntentMs=${if (it.finalTranscriptAt > 0L && it.intentResolvedAt > 0L) it.intentResolvedAt - it.finalTranscriptAt else -1L} " +
                    "intentToVisualFrameMs=${if (it.intentResolvedAt > 0L && it.frameReadyAt > 0L) it.frameReadyAt - it.intentResolvedAt else -1L} " +
                    "visualFrameAcquisitionMs=${if (it.frameRequestedAt > 0L && it.frameReadyAt > 0L) it.frameReadyAt - it.frameRequestedAt else -1L} " +
                    "speechEndToFrameReadyMs=${if (it.speechEndedAt > 0L && it.frameReadyAt > 0L) it.frameReadyAt - it.speechEndedAt else -1L} " +
                    "frameReadyToModelRequestMs=${if (it.frameReadyAt > 0L && it.modelRequestAt > 0L) it.modelRequestAt - it.frameReadyAt else -1L} " +
                    "modelRequestToFirstResponseMs=${if (it.modelRequestAt > 0L && it.firstModelResponseAt > 0L) it.firstModelResponseAt - it.modelRequestAt else -1L} " +
                    "responseToActionMs=${if (it.firstModelResponseAt > 0L && it.actionExecutedAt > 0L) it.actionExecutedAt - it.firstModelResponseAt else -1L} " +
                    "speechEndToActionMs=${if (it.speechEndedAt > 0L && it.actionExecutedAt > 0L) it.actionExecutedAt - it.speechEndedAt else -1L} " +
                    "speechEndToFirstAudioMs=${if (it.speechEndedAt > 0L && it.firstAudioAt > 0L) it.firstAudioAt - it.speechEndedAt else -1L} " +
                    "visualResultToReplyQueuedMs=${if (it.firstModelResponseAt > 0L && it.replyQueuedAt > 0L) it.replyQueuedAt - it.firstModelResponseAt else -1L} " +
                    "replyQueuedToFirstAudioMs=${if (it.replyQueuedAt > 0L && it.firstAudioAt > 0L) it.firstAudioAt - it.replyQueuedAt else -1L} " +
                    "speechEndToFirstPlaybackMs=${if (it.speechEndedAt > 0L && it.firstPlaybackAt > 0L) it.firstPlaybackAt - it.speechEndedAt else -1L} " +
                    "totalVisualTurnMs=${now - it.startedAt}"
            )
            fastVisualTurns.finish(it.id)
        }
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
        screenResponseAccessibilityPackage = ""
        screenResponseAccessibilityGeneration = 0L
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
        is AppCommand.PlayYouTube, AppCommand.OpenYouTubeShorts,
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
        if (command is AppCommand.ScrollYouTube) {
            // Every non-final caller is reduced to a proposal here. Physical scroll is
            // reachable only from handleScrollProposal(FINAL_AUTHORIZED).
            handleScrollProposal(command, "legacy_command_boundary", ScrollProposalAuthorization.PRE_FINAL)
            return
        }
        if (localCommandExecutedThisTurn || !shouldExecute(command)) return
        if (command is AppCommand.SearchYouTube) {
            voiceLog(
                "SEARCH_EXECUTOR_ENTRY class=MyraVoiceService method=executeCommand turnId=$activeTurnId " +
                    "finalTranscript=legacy query=${command.query.take(120)} destination=YOUTUBE " +
                    "foregroundPackage=${AccessibilityHelperService.instance?.currentForegroundContext()?.packageName}"
            )
        }
        localCommandExecutedThisTurn = true
        latestActionDispatchedAt = android.os.SystemClock.elapsedRealtime()
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
                                    if (!screenActionRegistry.isCurrent(
                        actionIntent.actionId, actionIntent.turnId, actionIntent.screenSessionId
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

    private fun executeYouTubeSemanticAction(command: YouTubeSemanticCommand): Boolean {
        val accessibility = AccessibilityHelperService.instance ?: return false
        val foreground = accessibility.currentForegroundContext() ?: return false
        val isYouTube = foreground.packageName.equals("com.google.android.youtube", true)
        if (!isYouTube) {
            textComposeSession.cancel()
            return false
        }
        textComposeSession.invalidateUnless(foreground.packageName, foreground.windowId, foreground.generation)
        if (command == YouTubeSemanticCommand.CancelComment && textComposeSession.snapshot() == null) return false
        val scope = com.myra.assistant.screen.ForegroundActionPolicy.scope(foreground) ?: return false
        if (!screenCommandTurnGuard.tryCommit(activeTurnId)) {
            voiceLog("youtube_semantic_duplicate_dropped turnId=$activeTurnId command=${command.javaClass.simpleName}")
            return true
        }
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        cancelSpeechForNewAction()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        latestActionDispatchedAt = startedAt
        val before = accessibility.visibleScreenSignature()

        if (command == YouTubeSemanticCommand.SendComment &&
            !textComposeSession.canSend(foreground.packageName, foreground.windowId, foreground.generation)
        ) {
            finishYouTubeSemantic(false, "Pehle comment type karo.", command, startedAt, "no_owned_draft")
            return true
        }
        if (command is YouTubeSemanticCommand.TypeText && textComposeSession.snapshot() == null) {
            finishYouTubeSemantic(false, "Pehle comments kholo.", command, startedAt, "comment_context_missing")
            return true
        }
        if (command == YouTubeSemanticCommand.CancelComment) {
            textComposeSession.cancel()
            finishYouTubeSemantic(true, "Theek hai, comment send nahi kiya.", command, startedAt, "cancelled")
            return true
        }

        val result = accessibility.performYouTubeSemanticAction(command, scope)
        voiceLog(
            "youtube_semantic_resolution turnId=$activeTurnId normalizedIntent=${command.javaClass.simpleName} " +
                "package=${foreground.packageName} role=${result.role} resolution=${result.resolution} " +
                "payloadLength=${(command as? YouTubeSemanticCommand.TypeText)?.payload?.length ?: 0} " +
                "dispatchMs=${android.os.SystemClock.elapsedRealtime() - startedAt}"
        )
        if (!result.accepted) {
            if (canUseVisualFallback(command) && visualAwarenessPreferences.enabled &&
                requestAccessibilityVisualRetry(command, foreground, activeTurnId, startedAt)
            ) {
                return true
            }
            val message = when (result.resolution) {
                "ambiguous" -> "Kaunsa wala?"
                "stale_foreground", "stale_candidate" -> "Screen badal gayi. Dobara target batao."
                "not_found" -> when (command) {
                    is YouTubeSemanticCommand.OpenChannel -> "Channel target clear nahi mila."
                    is YouTubeSemanticCommand.TypeText -> "Comment field clear nahi mila."
                    YouTubeSemanticCommand.SendComment -> "Comment ka send button nahi mila."
                    else -> "Ye control current YouTube screen par clear nahi mila."
                }
                else -> "YouTube action accept nahi hua."
            }
            finishYouTubeSemantic(false, message, command, startedAt, result.resolution)
            return true
        }

        when (command) {
            YouTubeSemanticCommand.OpenComments -> mainHandler.postDelayed({
                val current = accessibility.currentForegroundContext()
                if (current != null && current.packageName.equals("com.google.android.youtube", true)) {
                    textComposeSession.open(current.packageName, current.windowId, current.generation)
                }
            }, 450L)
            is YouTubeSemanticCommand.TypeText -> {
                val field = result.fieldIdentity
                if (field == null || !textComposeSession.setDraft(
                        foreground.packageName, foreground.windowId, foreground.generation, field, command.payload
                    )) {
                    finishYouTubeSemantic(false, "Comment context badal gaya. Dobara comments kholo.", command, startedAt, "field_ownership_rejected")
                    return true
                }
            }
            YouTubeSemanticCommand.SendComment -> textComposeSession.cancel()
            else -> Unit
        }
        val message = when {
            result.resolution == "already_active" && command == YouTubeSemanticCommand.Like -> "Video pehle se liked hai."
            result.resolution == "already_active" && command == YouTubeSemanticCommand.Subscribe -> "Channel pehle se subscribed hai."
            command is YouTubeSemanticCommand.TypeText -> "Comment type ho gaya."
            command == YouTubeSemanticCommand.SendComment -> "Comment post ho gaya."
            command == YouTubeSemanticCommand.OpenComments -> "Comments open ho gaye."
            command == YouTubeSemanticCommand.Like -> "Video like ho gaya."
            command == YouTubeSemanticCommand.Subscribe -> "Subscribe ho gaya."
            command is YouTubeSemanticCommand.OpenChannel -> "Channel open ho gaya."
            else -> "Open ho gaya."
        }
        mainHandler.postDelayed({
            val stillOwned = com.myra.assistant.screen.ForegroundActionPolicy.canExecute(scope, accessibility.currentForegroundContext())
            val changed = before.isNotBlank() && accessibility.visibleScreenSignature() != before
            voiceLog("youtube_semantic_verification command=${command.javaClass.simpleName} stillOwned=$stillOwned changed=$changed")
            finishYouTubeSemantic(true, message, command, startedAt, if (changed) "verified_change" else "accepted_no_repeat")
        }, if (command is YouTubeSemanticCommand.TypeText) 120L else 380L)
        return true
    }

    private fun handleUnifiedActionFollowUp() {
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        cancelSpeechForNewAction()
        AccessibilityHelperService.instance?.refreshScreenContext()
        val working = WorkingTaskRuntime.store.snapshot()
        val message = when (working.lastVerifiedSuccess) {
            true -> "Haan, pichhla action verify ho gaya tha."
            false -> "Haan, abhi result verify nahi hua."
            null -> "Abhi result verify nahi hua; current screen dobara check karni hogi."
        }
        listener?.onMyraText(message, working.lastVerifiedSuccess != true)
        emitState(message)
        queueLocalSpeech(message, allowUntranscribedAudio = false)
        voiceLog(
            "agent_verification_follow_up taskId=${working.taskId} lastAction=${working.lastRequestedAction} " +
                "verified=${working.lastVerifiedSuccess}"
        )
    }

    private fun runtimePerception(taskId: String): PerceptionSnapshot? {
        val context = ActivityContextStore.snapshot() ?: return null
        val scene = ScreenSceneFactory.from(context, WorkingTaskRuntime.store.snapshot().activeExternalApp)
        return PerceptionSnapshot(scene, taskId, android.os.SystemClock.elapsedRealtime())
    }

    /** Production owner for migrated capabilities. Planner output is the only path to an
     * adapter; the service schedules observation but never calls the low-level executor twice. */
    private fun executeGeneralRuntimeCapability(
        expectedCapability: ToolCapability,
        requestedTurnId: Long,
        requestedTaskId: String,
        onTerminal: (GeneralVerificationStatus, String) -> Unit
    ): Boolean {
        val runtime = GeneralAgentRuntimeStore.runtime
        val task = runtime.activeTask() ?: return false
        if (!RuntimeActionBindingGuard.matches(requestedTurnId, requestedTaskId, task.turnId, task.id)) {
            voiceLog(
                "AGENT_RUNTIME_TURN_MISMATCH requestedTurnId=$requestedTurnId taskTurnId=${task.turnId} " +
                    "requestedTaskId=$requestedTaskId activeTaskId=${task.id} action=$expectedCapability decision=blocked"
            )
            voiceLog(
                "VOICE_ACTION_IDENTITY_INVALID speechTurnId=$requestedTurnId runtimeTurnId=${task.turnId} " +
                    "reason=runtime_task_identity_mismatch"
            )
            return false
        }
        val enteredAt = android.os.SystemClock.elapsedRealtime()
        voiceLog("AGENT_RUNTIME_ENTER taskId=${task.id} turnId=${task.turnId} capability=$expectedCapability recoveryCount=${task.recoveryCount}")
        var before = runtimePerception(task.id)
        if (before == null) {
            AccessibilityHelperService.instance?.refreshScreenContext(force = true)
            before = runtimePerception(task.id)
        }
        voiceLog("PLANNER_STARTED taskId=${task.id} turnId=${task.turnId} status=${task.status} recoveryCount=${task.recoveryCount}")
        val planned = runtime.next(before)
        runtime.activeTask()?.let { WorkingTaskRuntime.store.syncRuntime(it, before?.scene) }
        voiceLog("PLANNER_RESULT taskId=${task.id} turnId=${task.turnId} result=${planned.javaClass.simpleName}")
        if (planned is PlannerResult.NeedObservation) {
            AccessibilityHelperService.instance?.refreshScreenContext(force = true)
            val observed = runtimePerception(task.id)
            if (observed == null) {
                runtime.completeFromAdapter(GeneralVerificationStatus.UNKNOWN, "screen unavailable")
                onTerminal(GeneralVerificationStatus.UNKNOWN, "screen unavailable")
                return true
            }
            return executeGeneralRuntimeCapability(expectedCapability, requestedTurnId, requestedTaskId, onTerminal)
        }
        if (planned is PlannerResult.NeedClarification || planned is PlannerResult.Fail || planned is PlannerResult.Complete) {
            val observed = when (planned) {
                is PlannerResult.NeedClarification -> planned.message
                is PlannerResult.Fail -> planned.reason
                is PlannerResult.Complete -> planned.reason
                else -> "planner stopped"
            }
            val status = if (planned is PlannerResult.Fail) GeneralVerificationStatus.FAILURE else GeneralVerificationStatus.UNKNOWN
            runtime.completeFromAdapter(status, observed)
            onTerminal(status, observed)
            return true
        }
        val step = when (planned) {
            is PlannerResult.Next -> planned.step
            is PlannerResult.Recover -> planned.step
            is PlannerResult.VerifyPrevious -> planned.step
            else -> return false
        }
        val actionBefore = before
        if (step.capability != expectedCapability || actionBefore == null) {
            runtime.completeFromAdapter(GeneralVerificationStatus.FAILURE, "planner capability mismatch")
            onTerminal(GeneralVerificationStatus.FAILURE, "planner capability mismatch")
            return true
        }
        val adapter = generalActionRouter.select(step, actionBefore)
        if (adapter == null) {
            voiceLog("LEGACY_FALLBACK_USED taskId=${task.id} turnId=${task.turnId} capability=${step.capability} reason=no_registered_adapter")
            runtime.completeFromAdapter(GeneralVerificationStatus.FAILURE, "no registered adapter")
            onTerminal(GeneralVerificationStatus.FAILURE, "no registered adapter")
            return true
        }
        voiceLog(
            "ACTION_ROUTER_SELECTED taskId=${task.id} turnId=${task.turnId} stepId=${step.id} capability=${step.capability} " +
                "adapter=${adapter.adapterId} foregroundPackage=${actionBefore.scene.externalForegroundPackage} screenGeneration=${actionBefore.scene.generation}"
        )
        voiceLog("ACTION_ADAPTER_ENTER taskId=${task.id} turnId=${task.turnId} stepId=${step.id} capability=${step.capability} adapter=${adapter.adapterId}")
        voiceLog("ACTION_STARTED taskId=${task.id} turnId=${task.turnId} stepId=${step.id} capability=${step.capability}")
        val actionStartedAt = android.os.SystemClock.elapsedRealtime()
        val result = adapter.execute(step, actionBefore)
        val actionReturnedAt = android.os.SystemClock.elapsedRealtime()
        runtime.recordAction(step, result, actionBefore)
        runtime.activeTask()?.let { WorkingTaskRuntime.store.syncRuntime(it, actionBefore.scene) }
        voiceLog(
            "ACTION_RETURNED taskId=${task.id} turnId=${task.turnId} stepId=${step.id} capability=${step.capability} " +
                "adapter=${adapter.adapterId} accepted=${result.accepted} elapsedMs=${android.os.SystemClock.elapsedRealtime() - enteredAt}"
        )
        lateinit var observeAndVerify: (Int) -> Unit
        observeAndVerify = observe@ { resampleCount ->
            val current = runtime.activeTask()
            if (current?.id != task.id || current.turnId != task.turnId) return@observe
            val observationStartedAt = android.os.SystemClock.elapsedRealtime()
            voiceLog(
                "POST_ACTION_OBSERVATION_STARTED taskId=${task.id} turnId=${task.turnId} " +
                    "stepId=${step.id} capability=${step.capability} resampleCount=$resampleCount"
            )
            AccessibilityHelperService.instance?.refreshScreenContext(force = true)
            val after = runtimePerception(task.id)
            if (after == null) {
                if (step.capability == ToolCapability.ACCESSIBILITY_SCROLL &&
                    ScrollVerificationResamplePolicy.shouldResample(result.accepted, false, resampleCount)
                ) {
                    voiceLog(
                        "SCROLL_VERIFY_RESAMPLE_SCHEDULED taskId=${task.id} turnId=${task.turnId} " +
                            "resample=${resampleCount + 1} reason=fresh_scene_unavailable delayMs=${ScrollVerificationResamplePolicy.DELAY_MS}"
                    )
                    mainHandler.postDelayed({
                        voiceLog("SCROLL_VERIFY_RESAMPLE_READY taskId=${task.id} turnId=${task.turnId} resample=${resampleCount + 1}")
                        observeAndVerify(resampleCount + 1)
                    }, ScrollVerificationResamplePolicy.DELAY_MS)
                    return@observe
                }
                runtime.completeFromAdapter(GeneralVerificationStatus.UNKNOWN, "fresh screen unavailable")
                onTerminal(GeneralVerificationStatus.UNKNOWN, "fresh screen unavailable")
                return@observe
            }
            voiceLog(
                "POST_ACTION_OBSERVATION_READY taskId=${task.id} turnId=${task.turnId} stepId=${step.id} " +
                    "foregroundPackage=${after.scene.externalForegroundPackage} screenGeneration=${after.scene.generation}"
            )
            voiceLog("VERIFICATION_STARTED taskId=${task.id} turnId=${task.turnId} stepId=${step.id} expected=${step.expectedOutcome.summary}")
            val scrollEvidence = if (step.capability == ToolCapability.ACCESSIBILITY_SCROLL) {
                ScrollMovementAnalyzer.analyze(actionBefore.scene, after.scene).also { evidence ->
                voiceLog(
                    "SCROLL_VERIFICATION_EVIDENCE taskId=${task.id} turnId=${task.turnId} " +
                        "preGeneration=${actionBefore.scene.generation} postGeneration=${after.scene.generation} " +
                        "stableAnchorCount=${evidence.stableAnchorCount} movedAnchorCount=${evidence.movedAnchorCount} " +
                        "medianDeltaY=${evidence.medianDeltaY} newVisibleElements=${evidence.newVisibleElements} " +
                        "lostVisibleElements=${evidence.lostVisibleElements} scrollStateBefore=unavailable " +
                        "scrollStateAfter=unavailable accessibilityScrollEvent=unavailable decision=${if (evidence.proven) "SUCCESS" else "UNKNOWN"}"
                )
                }
            } else null
            if (scrollEvidence != null && ScrollVerificationResamplePolicy.shouldResample(
                    result.accepted, scrollEvidence.proven, resampleCount
                )
            ) {
                voiceLog(
                    "SCROLL_VERIFY_RESAMPLE_SCHEDULED taskId=${task.id} turnId=${task.turnId} " +
                        "resample=${resampleCount + 1} reason=unstable_or_insufficient_semantic_evidence " +
                        "delayMs=${ScrollVerificationResamplePolicy.DELAY_MS}"
                )
                mainHandler.postDelayed({
                    voiceLog("SCROLL_VERIFY_RESAMPLE_READY taskId=${task.id} turnId=${task.turnId} resample=${resampleCount + 1}")
                    observeAndVerify(resampleCount + 1)
                }, ScrollVerificationResamplePolicy.DELAY_MS)
                return@observe
            }
            if (scrollEvidence != null) {
                voiceLog(
                    "SCROLL_VERIFY_FINAL_EVIDENCE taskId=${task.id} turnId=${task.turnId} samples=${resampleCount + 1} " +
                        "stableAnchorCount=${scrollEvidence.stableAnchorCount} movedAnchorCount=${scrollEvidence.movedAnchorCount} " +
                        "medianDeltaY=${scrollEvidence.medianDeltaY} newVisibleElements=${scrollEvidence.newVisibleElements} " +
                        "lostVisibleElements=${scrollEvidence.lostVisibleElements} decision=${if (scrollEvidence.proven) "SUCCESS" else "UNKNOWN"}"
                )
            }
            val (verification, recovery) = runtime.verify(after)
            val verificationAt = android.os.SystemClock.elapsedRealtime()
            (runtime.activeTask() ?: runtime.lastCompletedTask())?.let { WorkingTaskRuntime.store.syncRuntime(it, after.scene) }
            voiceLog(
                "VERIFICATION_RESULT taskId=${task.id} turnId=${task.turnId} stepId=${step.id} status=${verification.status} " +
                    "expected=${verification.expected} observed=${verification.observed} recoveryCount=${runtime.activeTask()?.recoveryCount ?: task.recoveryCount}"
            )
            if (step.capability == ToolCapability.ACCESSIBILITY_CLICK || step.capability == ToolCapability.BACK) {
                voiceLog(
                    "SEMANTIC_VERIFICATION_RESULT taskId=${task.id} turnId=${task.turnId} capability=${step.capability} " +
                        "status=${verification.status} expected=${verification.expected} observed=${verification.observed}"
                )
            }
            voiceLog(
                "ACTION_LATENCY_BREAKDOWN turnId=${task.turnId} capability=${step.capability} " +
                    "speechEndToAuthorizationMs=${if (speechActivityEndedAt > 0L) latestIntentDecidedAt - speechActivityEndedAt else -1L} " +
                    "taskToRuntimeMs=${if (latestIntentDecidedAt > 0L) enteredAt - latestIntentDecidedAt else -1L} " +
                    "runtimeToActionStartedMs=${actionStartedAt - enteredAt} actionElapsedMs=${actionReturnedAt - actionStartedAt} " +
                    "actionReturnToObservationMs=${observationStartedAt - actionReturnedAt} " +
                    "observationToVerificationMs=${verificationAt - observationStartedAt} " +
                    "speechEndToVisibleActionEstimateMs=${if (speechActivityEndedAt > 0L) actionReturnedAt - speechActivityEndedAt else -1L}"
            )
            WorkingTaskRuntime.store.recordOutcome(
                verification.observed,
                verification.status == GeneralVerificationStatus.SUCCESS,
                result.targetId.takeIf { verification.status != GeneralVerificationStatus.SUCCESS },
                runtimeOwnsRecoveryCount = true
            )
            if (verification.status == GeneralVerificationStatus.SUCCESS) {
                runtime.lastCompletedTask()?.takeIf { expectedCapability != ToolCapability.BROWSER_SEARCH }?.let {
                    WorkingTaskRuntime.store.completeRuntime(it, verification.observed, TaskCompletionState.SUCCESS)
                }
                voiceLog("AGENT_TASK_TERMINAL taskId=${task.id} turnId=${task.turnId} status=SUCCESS elapsedMs=${android.os.SystemClock.elapsedRealtime() - enteredAt}")
                onTerminal(verification.status, verification.observed)
            } else if (recovery is RecoveryDecision.Retry) {
                voiceLog("RECOVERY_STARTED taskId=${task.id} turnId=${task.turnId} stepId=${step.id} recoveryCount=${runtime.activeTask()?.recoveryCount}")
                voiceLog("RECOVERY_DECISION taskId=${task.id} turnId=${task.turnId} decision=RETRY rejected=${recovery.rejectedTarget}")
                if (expectedCapability == ToolCapability.ACCESSIBILITY_CLICK) {
                    voiceLog("TARGET_REJECTED_FOR_RECOVERY taskId=${task.id} target=${recovery.rejectedTarget}")
                    voiceLog("ALTERNATE_TARGET_SELECTED taskId=${task.id} decision=RESOLVE_FRESH_EXCLUDING_REJECTED")
                }
                executeGeneralRuntimeCapability(expectedCapability, requestedTurnId, requestedTaskId, onTerminal)
            } else {
                voiceLog("RECOVERY_DECISION taskId=${task.id} turnId=${task.turnId} decision=${recovery?.javaClass?.simpleName ?: "NONE"}")
                runtime.completeFromAdapter(verification.status, verification.observed)
                runtime.lastCompletedTask()?.takeIf { expectedCapability != ToolCapability.BROWSER_SEARCH }?.let {
                    WorkingTaskRuntime.store.completeRuntime(
                        it, verification.observed,
                        if (verification.status == GeneralVerificationStatus.FAILURE) TaskCompletionState.FAILURE else TaskCompletionState.UNKNOWN
                    )
                }
                voiceLog("AGENT_TASK_TERMINAL taskId=${task.id} turnId=${task.turnId} status=${verification.status} elapsedMs=${android.os.SystemClock.elapsedRealtime() - enteredAt}")
                onTerminal(verification.status, verification.observed)
            }
        }
        mainHandler.postDelayed({ observeAndVerify(0) }, adapter.observationDelayMs)
        return true
    }

    private fun executeGeneralScrollAdapter(parameters: Map<String, String>): GeneralActionResult {
        val accessibility = AccessibilityHelperService.instance
            ?: return GeneralActionResult(false, failureReason = "accessibility_unavailable")
        val explicitYouTube = parameters["explicitApp"].equals("YouTube", true)
        val direction = parameters["direction"] ?: "DOWN"
        val down = direction != "UP"
        val foreground = accessibility.currentForegroundContext()
        val scope = com.myra.assistant.screen.ForegroundActionPolicy.scope(foreground)
        val accepted = when {
            explicitYouTube -> accessibility.scrollYouTubeVerified(down) { }
            scope == null -> false
            scope.expectedPackage.equals("com.google.android.youtube", true) ->
                accessibility.scrollYouTubeForegroundVerified(scope, down) { }
            else -> accessibility.scrollCurrentForegroundVerified(scope, down) { }
        }
        return GeneralActionResult(accepted, failureReason = "scroll_dispatch_rejected".takeIf { !accepted }, metadata = mapOf("direction" to direction))
    }

    private fun semanticTargetRequest(step: com.myra.assistant.agent.GeneralPlanStep): com.myra.assistant.agent.SemanticTargetRequest =
        com.myra.assistant.agent.SemanticTargetRequest(
            description = step.targetDescription.orEmpty(),
            roleHint = step.parameters["role"]?.let { runCatching { com.myra.assistant.agent.SemanticRole.valueOf(it) }.getOrNull() },
            textHint = step.parameters["text"],
            spatialHint = step.parameters["spatial"]?.let { runCatching { com.myra.assistant.agent.SpatialHint.valueOf(it) }.getOrNull() },
            ordinal = step.parameters["ordinal"]?.toIntOrNull(),
            relativeToElementId = step.parameters["reference"],
            currentGoal = WorkingTaskRuntime.store.snapshot().searchQuery
                ?: WorkingTaskRuntime.store.snapshot().lastCompletedTask?.query
        )

    private fun resolveGeneralSemanticTarget(
        step: com.myra.assistant.agent.GeneralPlanStep,
        perception: com.myra.assistant.agent.PerceptionSnapshot
    ): com.myra.assistant.agent.SemanticTargetResolution {
        voiceLog("TARGET_RESOLUTION_STARTED taskId=${step.taskId} goal=${step.targetDescription.orEmpty().take(160)}")
        val resolution = com.myra.assistant.agent.GeneralSemanticTargetResolver().resolve(
            semanticTargetRequest(step), perception.scene,
            GeneralAgentRuntimeStore.runtime.activeTask()?.rejectedTargets.orEmpty()
        )
        val scored = when (resolution) {
            is com.myra.assistant.agent.SemanticTargetResolution.Unique ->
                listOf(com.myra.assistant.agent.TargetCandidateScore(resolution.element, resolution.confidence, resolution.matchingReasons)) + resolution.alternatives
            is com.myra.assistant.agent.SemanticTargetResolution.Ambiguous -> resolution.scored
            else -> emptyList()
        }
        scored.take(5).forEach { candidate ->
            voiceLog(
                "TARGET_CANDIDATE elementId=${candidate.element.id} role=${candidate.element.role} " +
                    "text=${candidate.element.label.take(100)} position=${candidate.element.horizontalPosition}_${candidate.element.verticalPosition} " +
                    "score=${candidate.score} reasons=${candidate.reasons.joinToString("|")}"
            )
        }
        when (resolution) {
            is com.myra.assistant.agent.SemanticTargetResolution.Unique -> voiceLog(
                "TARGET_RESOLUTION_RESULT decision=FOUND_UNIQUE selectedElementId=${resolution.element.id} " +
                    "confidence=${resolution.confidence} alternativeCount=${resolution.alternatives.size}"
            )
            is com.myra.assistant.agent.SemanticTargetResolution.Ambiguous -> {
                voiceLog("TARGET_RESOLUTION_RESULT decision=FOUND_AMBIGUOUS selectedElementId= confidence=0 alternativeCount=${resolution.candidates.size}")
                voiceLog("CLARIFICATION_REQUIRED taskId=${step.taskId} reason=target_ambiguity")
            }
            com.myra.assistant.agent.SemanticTargetResolution.NotFound ->
                voiceLog("TARGET_RESOLUTION_RESULT decision=NOT_FOUND selectedElementId= confidence=0 alternativeCount=0")
        }
        return resolution
    }

    private fun executeGeneralFindElementAdapter(
        step: com.myra.assistant.agent.GeneralPlanStep,
        perception: com.myra.assistant.agent.PerceptionSnapshot
    ): GeneralActionResult = when (val resolution = resolveGeneralSemanticTarget(step, perception)) {
        is com.myra.assistant.agent.SemanticTargetResolution.Unique -> GeneralActionResult(true, resolution.element.id,
            metadata = mapOf("confidence" to resolution.confidence.toString()))
        is com.myra.assistant.agent.SemanticTargetResolution.Ambiguous -> GeneralActionResult(false, failureReason = "target_ambiguous")
        com.myra.assistant.agent.SemanticTargetResolution.NotFound -> GeneralActionResult(false, failureReason = "target_not_found")
    }

    private fun executeGeneralSemanticTapAdapter(
        step: com.myra.assistant.agent.GeneralPlanStep,
        perception: com.myra.assistant.agent.PerceptionSnapshot
    ): GeneralActionResult {
        if (com.myra.assistant.agent.ModalSafetyPolicy.requiresAuthorization(perception.scene)) {
            return GeneralActionResult(false, failureReason = "protected_modal")
        }
        val resolution = resolveGeneralSemanticTarget(step, perception)
        val unique = resolution as? com.myra.assistant.agent.SemanticTargetResolution.Unique
            ?: return GeneralActionResult(false, failureReason = when (resolution) {
                is com.myra.assistant.agent.SemanticTargetResolution.Ambiguous -> "target_ambiguous"
                else -> "target_not_found"
            })
        val bound = unique.element.copy(
            packageName = perception.scene.externalForegroundPackage,
            windowId = perception.scene.windowId,
            screenGeneration = perception.scene.generation
        )
        val safetyTarget = com.myra.assistant.agent.ResolvedActionTarget(
            bound.id, bound.packageName.orEmpty(), bound.windowId ?: -1, bound.screenGeneration ?: -1,
            listOf(bound.left, bound.top, bound.right, bound.bottom)
        )
        if (!com.myra.assistant.agent.GeneralActionSafety.validate(safetyTarget, perception)) {
            voiceLog("TARGET_STALE_REJECTED taskId=${step.taskId} elementId=${bound.id} reason=scene_binding_mismatch")
            return GeneralActionResult(false, bound.id, "stale_target")
        }
        val result = AccessibilityHelperService.instance?.tapResolvedSemanticTarget(bound)
            ?: return GeneralActionResult(false, bound.id, "accessibility_unavailable")
        if (result.resolution.startsWith("stale")) {
            voiceLog("TARGET_STALE_REJECTED taskId=${step.taskId} elementId=${bound.id} reason=${result.resolution}")
        }
        voiceLog("SEMANTIC_ACTION_DISPATCH taskId=${step.taskId} elementId=${bound.id} method=${result.method}")
        voiceLog("SEMANTIC_ACTION_RETURNED taskId=${step.taskId} elementId=${bound.id} accepted=${result.accepted} resolution=${result.resolution}")
        return GeneralActionResult(result.accepted, bound.id, result.resolution.takeIf { !result.accepted },
            mapOf("method" to result.method, "fingerprint" to com.myra.assistant.agent.SemanticTargetFingerprint.of(bound)))
    }

    /** Final-turn production owner for generic semantic tap and Back. */
    private fun executeGenericSemanticRuntime(task: com.myra.assistant.agent.AgentTask?): Boolean {
        val goal = task?.interpretedGoal ?: return false
        val capability = when (goal) {
            com.myra.assistant.agent.AgentGoalType.TAP -> ToolCapability.ACCESSIBILITY_CLICK
            com.myra.assistant.agent.AgentGoalType.NAVIGATE -> ToolCapability.BACK
            else -> return false
        }
        if (!screenCommandTurnGuard.tryCommit(activeTurnId)) {
            voiceLog("screen_command_duplicate_dropped turnId=$activeTurnId source=general_semantic_runtime")
            return true
        }
        val runtimeTask = GeneralAgentRuntimeStore.runtime.activeTask()
        if (runtimeTask == null || runtimeTask.turnId != activeTurnId || runtimeTask.id != task.id) {
            voiceLog(
                "AGENT_RUNTIME_TURN_MISMATCH requestedTurnId=$activeTurnId taskTurnId=${runtimeTask?.turnId} " +
                    "requestedTaskId=${task.id} activeTaskId=${runtimeTask?.id} action=$capability decision=blocked"
            )
            return true
        }
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        cancelSpeechForNewAction()
        AccessibilityHelperService.instance?.refreshScreenContext(force = true)
        val scene = ActivityContextStore.snapshot()?.let {
            com.myra.assistant.agent.ScreenSceneFactory.from(it, WorkingTaskRuntime.store.snapshot().activeExternalApp)
        }
        voiceLog(
            "SCREEN_SCENE_READY turnId=$activeTurnId package=${scene?.externalForegroundPackage} " +
                "windowId=${scene?.windowId} generation=${scene?.generation} elementCount=${scene?.semanticElements?.size ?: 0}"
        )
        return executeGeneralRuntimeCapability(capability, activeTurnId, runtimeTask.id) { status, observed ->
            val completed = GeneralAgentRuntimeStore.runtime.lastCompletedTask()
            val history = completed?.actionHistory?.lastOrNull()
            when (status) {
                GeneralVerificationStatus.SUCCESS -> {
                    history?.targetId?.let(WorkingTaskRuntime.store::recordReference)
                    emitState("Sun rahi hoon…")
                }
                GeneralVerificationStatus.UNKNOWN -> {
                    val message = "Result clear verify nahi hua. Screen par kaunsa target chahiye?"
                    listener?.onMyraText(message, true); queueLocalSpeech(message, allowUntranscribedAudio = false)
                }
                GeneralVerificationStatus.FAILURE -> {
                    val reason = history?.failureReason
                    val message = when (reason) {
                        "target_ambiguous", "clarification_required" -> "Kaunsa wala — upar wala ya neeche wala?"
                        "target_not_found" -> "Current screen par woh target clear nahi mila."
                        "protected_modal" -> "Protected dialog hai. Confirm karna hai ya cancel?"
                        else -> "Target open nahi hua. Kaunsa wala chahiye?"
                    }
                    voiceLog("CLARIFICATION_REQUIRED taskId=${task.id} reason=${reason ?: observed}")
                    listener?.onMyraText(message, true); queueLocalSpeech(message, allowUntranscribedAudio = false)
                }
            }
        }
    }

    private fun executeGeneralBrowserSearchAdapter(parameters: Map<String, String>): GeneralActionResult {
        val query = parameters["query"].orEmpty()
        if (query.isBlank()) return GeneralActionResult(false, failureReason = "missing_search_query")
        val destination = runCatching { SearchDestination.valueOf(parameters["destination"].orEmpty()) }.getOrDefault(SearchDestination.BROWSER)
        if (destination == SearchDestination.YOUTUBE) {
            val command = AppCommand.SearchYouTube(query)
            val result = assistantController.processCommand(
                StructuredCommandParser.fromLegacy(command, command.toString()), speak = false, notifyListeners = false
            )
            return GeneralActionResult(result.success, failureReason = "youtube_search_dispatch_failed".takeIf { !result.success })
        }
        val selected = parameters["executor"]?.takeIf { it.isNotBlank() }?.let {
            runCatching { com.myra.assistant.agent.BrowserSearchExecutor.valueOf(it) }.getOrNull()
        }
        val resolution = com.myra.assistant.agent.SearchResolution(
            destination, parameters["reason"].orEmpty(), selected, parameters["targetPackage"]?.takeIf { it.isNotBlank() }
        )
        val dispatch = BrowserSearchTool(this).execute(com.myra.assistant.agent.BrowserSearchRequest(query, destination), resolution)
        return GeneralActionResult(dispatch.accepted, failureReason = dispatch.reason.takeIf { !dispatch.accepted })
    }

    private fun executeUnifiedBrowserSearch(raw: String): Boolean {
        val request = BrowserSearchRequestParser.parse(raw) ?: return false
        val accessibility = AccessibilityHelperService.instance
        val freshForeground = accessibility?.currentForegroundContext()
        val working = WorkingTaskRuntime.store.snapshot()
        val resolution = SearchDestinationResolver.resolveDetailed(
            request,
            freshForeground?.packageName,
            working.activeExternalApp
        )
        voiceLog(
            "search_intent_resolved turnId=$activeTurnId finalTranscript=${raw.take(160)} query=${request.query.take(120)} " +
                "explicitDestination=${request.explicitDestination} workingContextDestination=${working.activeExternalApp} " +
                "foregroundPackage=${freshForeground?.packageName} resolvedDestination=${resolution.destination} " +
                "resolutionReason=${resolution.reason} selectedExecutor=${resolution.selectedExecutor}"
        )
        if (!screenCommandTurnGuard.tryCommit(activeTurnId)) return true
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        cancelSpeechForNewAction()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        latestActionDispatchedAt = startedAt
        val taskTurnId = activeTurnId
        val executorName = if (resolution.destination == SearchDestination.YOUTUBE) {
            "YOUTUBE"
        } else resolution.selectedExecutor?.name ?: "GENERIC_WEB"
        WorkingTaskRuntime.store.beginSearch(
            request.query, resolution.destination, executorName, "search_results_visible"
        )
        responseArbiter.claimControlled(taskTurnId)
        voiceLog(
            "SEARCH_TASK_CREATED turnId=$taskTurnId taskId=${WorkingTaskRuntime.store.snapshot().taskId} " +
                "queryLength=${request.query.length} destination=${resolution.destination}"
        )
        voiceLog(
            "SEARCH_RUNTIME_BOUND turnId=$taskTurnId taskId=${GeneralAgentRuntimeStore.runtime.activeTask()?.id} " +
                "destination=${resolution.destination} executor=$executorName"
        )
        voiceLog(
            "SEARCH_EXECUTOR_SELECTED turnId=$taskTurnId executor=$executorName " +
                "reason=${resolution.reason} targetPackage=${resolution.targetPackage}"
        )
        voiceLog(
            "SEARCH_EXECUTOR_ENTRY class=MyraVoiceService method=executeUnifiedBrowserSearch " +
                "turnId=$taskTurnId finalTranscript=${raw.take(160)} query=${request.query.take(120)} " +
                "destination=${resolution.destination} foregroundPackage=${freshForeground?.packageName}"
        )
        voiceLog(
            "task_result_owner turnId=$taskTurnId owner=CONTROLLED_AGENT taskId=${WorkingTaskRuntime.store.snapshot().taskId} " +
                "destination=${resolution.destination}"
        )
        voiceLog(
            "search_execution_started turnId=$taskTurnId destination=${resolution.destination} " +
                "reason=${resolution.reason} executor=$executorName"
        )
        val expectedPackage = resolution.targetPackage ?: when (resolution.destination) {
            SearchDestination.YOUTUBE -> "com.google.android.youtube"
            SearchDestination.BROWSER -> "com.android.chrome".takeIf {
                packageManager.getLaunchIntentForPackage(it) != null
            }
        }
        GeneralAgentRuntimeStore.runtime.enrich(
            mapOf(
                "query" to request.query,
                "destination" to resolution.destination.name,
                "executor" to resolution.selectedExecutor?.name.orEmpty(),
                "reason" to resolution.reason,
                "targetPackage" to expectedPackage.orEmpty()
            ),
            relevantApp = expectedPackage,
            textHint = request.query
        )
        val runtimeTaskId = GeneralAgentRuntimeStore.runtime.activeTask()?.id ?: return false
        return executeGeneralRuntimeCapability(ToolCapability.BROWSER_SEARCH, taskTurnId, runtimeTaskId) { status, observed ->
            val verification = when (status) {
                GeneralVerificationStatus.SUCCESS -> SearchVerification.SUCCESS
                GeneralVerificationStatus.FAILURE -> SearchVerification.FAILURE
                GeneralVerificationStatus.UNKNOWN -> SearchVerification.UNKNOWN
            }
            finishSearchTaskResult(taskTurnId, verification, observed)
        }
    }

    private fun finishSearchTaskResult(turnId: Long, verification: SearchVerification, observed: String) {
        val completion = when (verification) {
            SearchVerification.SUCCESS -> TaskCompletionState.SUCCESS
            SearchVerification.FAILURE -> TaskCompletionState.FAILURE
            SearchVerification.UNKNOWN -> TaskCompletionState.UNKNOWN
        }
        val completed = WorkingTaskRuntime.store.completeSearch(observed, completion)
        val task = WorkingTaskRuntime.store.snapshot()
        // Search is terminal here. Keep it only as completed history; do not write it
        // into BrainTaskState.lastAction where it could bias unrelated later turns.
        brain.clearTransientState()
        voiceLog(
            "task_verification_completed turnId=$turnId taskId=${completed.taskId} verification=$verification " +
                "destination=${completed.destination} observed=$observed activeTaskCleared=${task.completionState == null}"
        )
        voiceLog(
            "SEARCH_VERIFICATION_RESULT turnId=$turnId taskId=${completed.taskId} verification=$verification " +
                "destination=${completed.destination}"
        )
        voiceLog(
            "SEARCH_TASK_TERMINAL turnId=$turnId taskId=${completed.taskId} completionState=$completion " +
                "ordinaryModelMayReport=false activeTaskCleared=true"
        )
        responseArbiter.controlledGenerationComplete()
        responseArbiter.controlledPlaybackComplete()
        // Keep CONTROLLED_LOCAL ownership latched until the next real user turn begins.
        // Late packets from the interrupted ordinary model must never report this task.
        voiceLog("SEARCH_RESULT_OWNER turnId=$turnId owner=CONTROLLED_AGENT release=NEXT_USER_TURN")
        when (verification) {
            SearchVerification.SUCCESS -> {
                emitState("Sun rahi hoon…")
                voiceLog("task_result_spoken turnId=$turnId spoken=false result=SUCCESS")
                voiceLog("SEARCH_RESULT_PLAYBACK turnId=$turnId spoken=false result=SUCCESS")
            }
            SearchVerification.UNKNOWN -> {
                val message = "Search open hui, lekin results verify nahi hue."
                listener?.onMyraText(message, true)
                queueLocalSpeech(message, allowUntranscribedAudio = false)
                voiceLog("task_result_spoken turnId=$turnId spoken=true result=UNKNOWN destination=${completed.destination}")
                voiceLog("SEARCH_RESULT_PLAYBACK turnId=$turnId spoken=true result=UNKNOWN")
            }
            SearchVerification.FAILURE -> {
                check(SearchTaskResultPolicy.maySpeakFailure(verification))
                val destination = if (completed.destination == SearchDestination.YOUTUBE) "YouTube" else "Browser"
                val message = "$destination search start nahi ho paayi."
                listener?.onMyraText(message, true)
                queueLocalSpeech(message, allowUntranscribedAudio = false)
                voiceLog("task_result_spoken turnId=$turnId spoken=true result=FAILURE destination=${completed.destination}")
                voiceLog("SEARCH_RESULT_PLAYBACK turnId=$turnId spoken=true result=FAILURE")
            }
        }
    }

    private fun executeUnifiedReferenceIfApplicable(raw: String): Boolean {
        val normalized = raw.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()
        val isReference = listOf("hand wala", "thumb wala", "second wala", "doosra wala", "dusra wala",
            "ye wala", "isko kholo", "click this", "click that", "ye wala dabao", "woh nahi", "wo nahi")
            .any(normalized::contains)
        if (!isReference) return false
        val context = ActivityContextStore.snapshot() ?: return false
        val decision = UnifiedLyraAgentRuntime.agent.resolveReference(raw, context)
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        cancelSpeechForNewAction()
        when (decision) {
            is com.myra.assistant.agent.AgentDecision.Clarify -> {
                listener?.onMyraText(decision.message)
                emitState(decision.message)
                queueLocalSpeech(decision.message, allowUntranscribedAudio = true)
                voiceLog("agent_clarification taskId=${UnifiedLyraAgentRuntime.agent.currentTask()?.id} reason=ambiguous_reference")
            }
            is com.myra.assistant.agent.AgentDecision.Execute -> {
                val target = decision.target ?: return false
                val accessibility = AccessibilityHelperService.instance
                val foreground = accessibility?.currentForegroundContext()
                val scope = com.myra.assistant.screen.ForegroundActionPolicy.scope(foreground)
                if (accessibility == null || scope == null || context.packageName != scope.expectedPackage ||
                    context.windowId != scope.expectedWindowId || context.generation != ActivityContextStore.snapshot()?.generation
                ) {
                    val message = "Screen badal gayi. Dobara target batao."
                    listener?.onMyraText(message, true); queueLocalSpeech(message, allowUntranscribedAudio = false)
                    return true
                }
                val before = accessibility.visibleScreenSignature()
                val result = accessibility.resolveAndTapVisibleTarget(target.label, null, null, scope) { _, _ -> true }
                val taskId = UnifiedLyraAgentRuntime.agent.currentTask()?.id
                voiceLog("agent_action_dispatched taskId=$taskId tool=accessibility_click targetRole=${target.role} accepted=${result.accepted}")
                if (!result.accepted) {
                    UnifiedLyraAgentRuntime.agent.recordAction(
                        com.myra.assistant.agent.AgentActionRecord("accessibility_click", target.id, false, false, android.os.SystemClock.elapsedRealtime()),
                        ActivityContextStore.snapshot()
                    )
                    WorkingTaskRuntime.store.recordOutcome(result.resolution, false, target.id)
                    val message = if (result.resolution == "ambiguous") "Kaunsa wala?" else "Ye target clear nahi mila."
                    listener?.onMyraText(message, true); queueLocalSpeech(message, allowUntranscribedAudio = false)
                } else mainHandler.postDelayed({
                    accessibility.refreshScreenContext(force = true)
                    val changed = before.isNotBlank() && accessibility.visibleScreenSignature() != before
                    UnifiedLyraAgentRuntime.agent.recordAction(
                        com.myra.assistant.agent.AgentActionRecord("accessibility_click", target.id, true, changed, android.os.SystemClock.elapsedRealtime()),
                        ActivityContextStore.snapshot()
                    )
                    WorkingTaskRuntime.store.recordOutcome(if (changed) "screen_changed" else "no_verified_change", changed, target.id.takeIf { !changed })
                    voiceLog("agent_verification taskId=$taskId accepted=true verified=$changed")
                    if (!changed) {
                        val message = "Tap hua, lekin result verify nahi hua."
                        listener?.onMyraText(message, true); queueLocalSpeech(message, allowUntranscribedAudio = false)
                    }
                }, 350L)
            }
            is com.myra.assistant.agent.AgentDecision.ObserveMore -> {
                val accessibility = AccessibilityHelperService.instance
                if (!visualAwarenessPreferences.enabled || accessibility == null ||
                    !accessibility.requestVisualScreenshot { result ->
                        mainHandler.post {
                            val message = if (result.isSuccess) "Kaunsa wala?" else "Current screen clear nahi mili."
                            listener?.onMyraText(message, result.isFailure)
                            queueLocalSpeech(message, allowUntranscribedAudio = result.isSuccess)
                        }
                    }
                ) {
                    val message = "Kaunsa wala?"
                    listener?.onMyraText(message); queueLocalSpeech(message, allowUntranscribedAudio = true)
                }
            }
            else -> return false
        }
        return true
    }

    private fun finishYouTubeSemantic(
        success: Boolean,
        message: String,
        command: YouTubeSemanticCommand,
        startedAt: Long,
        resolution: String
    ) {
        if (!success) {
            listener?.onMyraText(message, true)
            emitState(message)
            queueLocalSpeech(message, allowUntranscribedAudio = false)
        }
        voiceLog(
            "youtube_semantic_finished command=${command.javaClass.simpleName} success=$success " +
                "resolution=$resolution spokenFeedbackSuppressed=$success totalMs=${android.os.SystemClock.elapsedRealtime() - startedAt}"
        )
    }

    private fun canUseVisualFallback(command: YouTubeSemanticCommand): Boolean = command in setOf(
        YouTubeSemanticCommand.Like, YouTubeSemanticCommand.OpenComments,
        YouTubeSemanticCommand.Subscribe, YouTubeSemanticCommand.Share, YouTubeSemanticCommand.More
    ) || command is YouTubeSemanticCommand.OpenChannel

    private fun executeScreenModeCommand(command: ScreenModeCommand) {
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        cancelSpeechForNewAction()
        when (command) {
            ScreenModeCommand.ON -> {
                if (ScreenCaptureService.currentState != ScreenShareState.ACTIVE) {
                    requestProjectionPermissionFromOwner()
                } else voiceLog("screen_mode_command mode=ON result=already_active spokenFeedbackSuppressed=true")
            }
            ScreenModeCommand.OFF -> {
                startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
                voiceLog("screen_mode_command mode=OFF result=stop_requested spokenFeedbackSuppressed=true")
            }
        }
    }

    private fun requestProjectionPermissionFromOwner() {
        if (ScreenCaptureService.currentState == ScreenShareState.ACTIVE) return
        val request = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_REQUEST_SCREEN_PROJECTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(request) }
            .onSuccess { voiceLog("screen_projection_permission_owner_requested owner=MainActivity") }
            .onFailure {
                voiceLog("continuous_screen_permission_failed error=${it.javaClass.simpleName}")
                listener?.onMyraText("Screen sharing permission open nahi hui.", true)
                queueLocalSpeech("Screen sharing permission open nahi hui.", allowUntranscribedAudio = false)
            }
    }

    private fun requestAccessibilityVisualRetry(
        command: YouTubeSemanticCommand,
        expected: com.myra.assistant.screen.ForegroundAppContext,
        turnId: Long,
        startedAt: Long
    ): Boolean {
        val current = AccessibilityHelperService.instance?.currentForegroundContext() ?: return false
        if (current.packageName != expected.packageName || current.windowId != expected.windowId ||
            current.generation != expected.generation
        ) return false
        voiceLog(
            "youtube_semantic_fallback route=FAST_VISUAL_TURN turnId=$turnId " +
                "role=${command.javaClass.simpleName} accessibilityElapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}"
        )
        // The deterministic attempt already owned the action guard. Visual fallback is
        // the same turn and becomes its sole response owner, not a competing action.
        screenCommandTurnGuard.clear()
        beginFreshScreenQuery(
            lastUserIntentText,
            turnId,
            FastVisualRequest(FastVisualKind.ACTION, command.javaClass.simpleName)
        )
        return true
    }

    private fun executeAccessibilityFirstScreenAction(
        target: ScreenTargetReference,
        ownedTarget: ScreenTargetReference,
        actionScope: com.myra.assistant.screen.ForegroundActionScope,
        taskToken: Long,
        accessibility: AccessibilityHelperService
    ): Boolean {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val before = accessibility.visibleScreenSignature()
        val actionSessionId = ScreenCaptureService.session.sessionId.takeIf(String::isNotBlank)
            ?: "accessibility:${actionScope.expectedPackage}:${actionScope.expectedGeneration}"
        var actionIntent: ScreenActionIntent? = null
        var resolution = "not_found"
        var candidateCount = 0
        var selectedLabel: String? = null
        val accepted = if (
            actionScope.expectedPackage.equals("com.google.android.youtube", true) &&
            target.ordinal != null &&
            target.targetText.orEmpty().contains("video", true)
        ) {
            actionIntent = screenActionRegistry.create(
                activeTurnId, actionSessionId, lastUserIntentText,
                target.targetText, target.position, target.ordinal,
                actionScope.expectedPackage, startedAt, 0L, 1.0,
                actionScope.expectedWindowId, actionScope.expectedGeneration
            )
            val result = accessibility.resolveAndTapYouTubeVideo(target.ordinal, actionScope)
            resolution = result.resolution
            candidateCount = result.candidateCount
            selectedLabel = result.selectedLabel
            result.accepted
        } else {
            val result = accessibility.resolveAndTapVisibleTarget(
                target.targetText, target.position, target.ordinal, actionScope
            ) { _, confidence ->
                actionIntent = screenActionRegistry.create(
                    activeTurnId, actionSessionId, lastUserIntentText,
                    target.targetText, target.position, target.ordinal,
                    actionScope.expectedPackage, startedAt, 0L, confidence,
                    actionScope.expectedWindowId, actionScope.expectedGeneration
                )
                true
            }
            resolution = result.resolution
            selectedLabel = result.candidate?.label
            result.accepted
        }
        voiceLog(
            "screen_action_path turnId=$activeTurnId path=ACCESSIBILITY_FAST_PATH " +
                "package=${actionScope.expectedPackage} ordinal=${target.ordinal} " +
                "candidateCount=$candidateCount resolution=$resolution " +
                "selected=${selectedLabel?.take(80)} dispatchMs=${android.os.SystemClock.elapsedRealtime() - startedAt}"
        )
        if (!accepted) {
            actionIntent?.let { screenActionRegistry.cancel(it.actionId) }
            return when (resolution) {
                "ambiguous" -> {
                    finishBrainTask(taskToken, false, "Kaunsa wala?")
                    true
                }
                "stale_foreground", "stale_candidate" -> {
                    finishBrainTask(taskToken, false, "Screen badal gayi, target use nahi kiya.")
                    true
                }
                "ordinal_out_of_range" -> {
                    finishBrainTask(taskToken, false, "Itne videos current screen par nahi mile.")
                    true
                }
                "no_video_candidates", "click_rejected" -> {
                    finishBrainTask(taskToken, false, "Current YouTube screen par real video target nahi mila.")
                    true
                }
                else -> false
            }
        }
        val intent = actionIntent ?: return false
        mainHandler.postDelayed({
            if (!brain.isTaskCurrent(taskToken) ||
                !screenActionRegistry.isCurrent(intent.actionId, intent.turnId, intent.screenSessionId)
            ) return@postDelayed
            val changed = before.isNotBlank() && accessibility.visibleScreenSignature() != before
            brain.recordScreenAction(ownedTarget, changed)
            screenActionRegistry.cancel(intent.actionId)
            finishBrainTask(
                taskToken,
                changed,
                if (changed) "Open ho gaya." else "Tap hua, lekin screen change verify nahi hua."
            )
            voiceLog(
                "screen_action_fast_result actionId=${intent.actionId} verified=$changed " +
                    "totalMs=${android.os.SystemClock.elapsedRealtime() - startedAt}"
            )
        }, 420L)
        return true
    }

    private fun executeContextualScreenAction(target: ScreenTargetReference) {
        suppressModelForTurn = true
        localCommandExecutedThisTurn = true
        waitingForFreshInputAfterCommand = true
        cancelSpeechForNewAction()
        val accessibility = AccessibilityHelperService.instance
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
        if (target.appPackage != null &&
            (target.appPackage != actionScope.expectedPackage ||
                target.activeWindowId != actionScope.expectedWindowId ||
                target.screenContextGeneration != actionScope.expectedGeneration)
        ) {
            finishBrainTask(taskToken, false, "Screen badal gayi hai. Kaunsa item?")
            return
        }
        val ownedTarget = target.copy(
            appPackage = actionScope.expectedPackage,
            activeWindowId = actionScope.expectedWindowId,
            screenContextGeneration = actionScope.expectedGeneration
        )
        if (executeAccessibilityFirstScreenAction(
                target, ownedTarget, actionScope, taskToken, accessibility
            )
        ) return
        if (!screenVisionPreferences.visionEnabled ||
            ScreenCaptureService.currentState != ScreenShareState.ACTIVE
        ) {
            finishBrainTask(taskToken, false, "Target Accessibility se clear nahi mila.")
            return
        }
        voiceLog(
            "screen_action_path turnId=$activeTurnId path=SCREEN_VISION_FALLBACK " +
                "package=${actionScope.expectedPackage}"
        )
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
                    brain.recordScreenAction(ownedTarget, false)
                    finishBrainTask(taskToken, false, "Doosra target clear nahi mila.")
                    return@post
                }
                mainHandler.postDelayed({
                    ScreenCaptureService.requestFreshFrame(activeTurnId) { result ->
                        mainHandler.post {
                            if (!brain.isTaskCurrent(taskToken)) return@post
                            val action = ownedAction ?: return@post
                            if (!screenActionRegistry.isCurrent(
                                action.actionId, action.turnId, action.screenSessionId
                            )) {
                                voiceLog("SCREEN_ACTION_CANCELLED actionId=${action.actionId} reason=replaced_before_verification")
                                return@post
                            }
                            val postFrame = (result as? FreshFrameResult.Ready)?.frame
                            val accessibilityChanged = beforeSignature.isNotBlank() &&
                                accessibility.visibleScreenSignature() != beforeSignature
                            val frameChanged = postFrame != null && postFrame.sessionId == beforeFrame.sessionId &&
                                postFrame.frameId > beforeFrame.frameId && postFrame.hash != beforeFrame.hash
                            val verified = ScreenCaptureService.session.isCurrent(beforeFrame.sessionId) &&
                                (accessibilityChanged || frameChanged)
                            brain.recordScreenAction(ownedTarget, verified)
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

    private fun handleScrollProposal(
        command: AppCommand.ScrollYouTube,
        source: String,
        authorization: ScrollProposalAuthorization,
        requestedTaskId: String? = null
    ): Boolean {
        val turnId = activeTurnId
        val direction = command.direction ?: lastScrollDirection
        val foreground = AccessibilityHelperService.instance?.currentForegroundContext()
        if (authorization == ScrollProposalAuthorization.PRE_FINAL) {
            if (turnId <= 0L) {
                voiceLog(
                    "SCROLL_CANDIDATE_REJECTED turnId=$turnId direction=$direction source=$source " +
                        "authorization=PRE_FINAL decision=REJECTED reason=missing_voice_turn"
                )
                return false
            }
            pendingScrollCandidates.stage(
                turnId = turnId,
                direction = direction.name,
                detectedAt = android.os.SystemClock.elapsedRealtime(),
                source = source,
                foregroundPackage = foreground?.packageName,
                windowId = foreground?.windowId,
                observedGeneration = foreground?.generation ?: 0L
            )
            voiceLog(
                "SCROLL_CANDIDATE_DETECTED turnId=$turnId direction=$direction source=$source " +
                    "foregroundPackage=${foreground?.packageName} observedGeneration=${foreground?.generation ?: 0L} " +
                    "authorization=PRE_FINAL decision=STAGED"
            )
            return false
        }

        val runtimeTask = GeneralAgentRuntimeStore.runtime.activeTask()
        if (runtimeTask == null || requestedTaskId.isNullOrBlank()) {
            voiceLog(
                "SCROLL_RUNTIME_MISSING turnId=$turnId reason=post_final_authoritative_task_missing " +
                    "authorization=FINAL_AUTHORIZED"
            )
            executeVerifiedScroll(command, requestedTurnId = turnId, requestedTaskId = requestedTaskId.orEmpty())
            return false
        }
        val candidate = pendingScrollCandidates.consume(turnId)
        val now = android.os.SystemClock.elapsedRealtime()
        val candidateCompatible = candidate?.let {
            ScrollCandidatePolicy.compatible(it, turnId, foreground?.packageName, foreground?.windowId, now) &&
                it.direction == direction.name
        } ?: false
        if (candidate != null && !candidateCompatible) {
            voiceLog(
                "SCROLL_CANDIDATE_REJECTED turnId=$turnId candidateTurnId=${candidate.turnId} " +
                    "candidateDirection=${candidate.direction} finalDirection=$direction source=${candidate.source} " +
                    "foregroundPackage=${foreground?.packageName} observedGeneration=${foreground?.generation ?: 0L} " +
                    "reason=stale_or_incompatible_final_intent"
            )
        }
        if (!screenCommandTurnGuard.tryCommit(turnId)) {
            voiceLog("SCROLL_RUNTIME_DUPLICATE_BLOCKED turnId=$turnId taskId=${runtimeTask.id} source=$source")
            return false
        }
        voiceLog(
            "SCROLL_RUNTIME_BOUND turnId=$turnId taskId=${runtimeTask.id} direction=$direction " +
                "source=$source stagedCandidateCompatible=$candidateCompatible"
        )
        voiceLog("SCROLL_FINAL_DISPATCH turnId=$turnId taskId=${runtimeTask.id} direction=$direction owner=FINAL_UNIFIED_TURN")
        executeVerifiedScroll(command, requestedTurnId = turnId, requestedTaskId = runtimeTask.id)
        return true
    }

    private fun executeVerifiedScroll(
        command: AppCommand.ScrollYouTube,
        requestedTurnId: Long = activeTurnId,
        requestedTaskId: String = GeneralAgentRuntimeStore.runtime.activeTask()?.id.orEmpty()
    ) {
        cancelSpeechForNewAction()
        suppressModelForTurn = true
        waitingForFreshInputAfterCommand = true
        commandProbe.clear()
        output.clear()
        mediaGuard.finishInteraction()

        val resolvedDirection = command.direction ?: lastScrollDirection
        val explicitYouTube = command.explicitlyRequestedApp.equals("YouTube", true)
        val liveForeground = AccessibilityHelperService.instance?.currentForegroundContext()
        brain.observeForegroundApp(liveForeground?.packageName)
        val actionScope = com.myra.assistant.screen.ForegroundActionPolicy.scope(liveForeground)
        val foregroundPackage = actionScope?.expectedPackage ?: ActivityContextStore.snapshot()?.packageName
        val path = when {
            explicitYouTube -> "ACCESSIBILITY_EXPLICIT_YOUTUBE"
            foregroundPackage.equals("com.google.android.youtube", true) -> "ACCESSIBILITY_YOUTUBE_FOREGROUND"
            else -> "ACCESSIBILITY_CURRENT_APP"
        }
        voiceLog(
            "screen_action_runtime_path turnId=$activeTurnId action=scroll path=$path " +
                "package=$foregroundPackage direction=$resolvedDirection"
        )
        GeneralAgentRuntimeStore.runtime.enrich(mapOf(
            "direction" to resolvedDirection.name,
            "explicitApp" to command.explicitlyRequestedApp.orEmpty(),
            "path" to path
        ), relevantApp = foregroundPackage)
        val owned = executeGeneralRuntimeCapability(
            ToolCapability.ACCESSIBILITY_SCROLL, requestedTurnId, requestedTaskId
        ) { status, _ ->
            when (status) {
                GeneralVerificationStatus.SUCCESS -> {
                    lastScrollDirection = resolvedDirection
                    hasAcknowledgedScrollDirection = true
                    audio?.setMuted(false)
                    emitState("Sun rahi hoon…")
                    voiceLog("screen_action_feedback_suppressed turnId=$activeTurnId action=scroll success=true owner=GENERAL_RUNTIME")
                }
                GeneralVerificationStatus.UNKNOWN -> {
                    val dispatchAccepted = GeneralAgentRuntimeStore.runtime.lastCompletedTask()
                        ?.actionHistory?.lastOrNull()?.accepted == true
                    if (dispatchAccepted) {
                        audio?.setMuted(false)
                        emitState("Sun rahi hoon…")
                        voiceLog(
                            "screen_action_feedback_suppressed turnId=$activeTurnId action=scroll " +
                                "success=unknown accepted=true owner=GENERAL_RUNTIME reason=insufficient_movement_evidence"
                        )
                    }
                }
                GeneralVerificationStatus.FAILURE -> {
                    val message = if (explicitYouTube) "YouTube ka current feed move nahi hua."
                    else "Current app ka scrollable area move nahi hua."
                    listener?.onMyraText(message, true); emitState(message); queueLocalSpeech(message)
                }
            }
        }
        if (!owned) {
            val reason = if (GeneralAgentRuntimeStore.runtime.activeTask() == null) "no_active_runtime_task" else "runtime_turn_mismatch"
            voiceLog("SCROLL_RUNTIME_MISSING turnId=$requestedTurnId reason=$reason")
            voiceLog("LEGACY_FALLBACK_USED turnId=$requestedTurnId capability=ACCESSIBILITY_SCROLL reason=$reason execution=blocked")
            val message = "Scroll task active nahi hai, isliye action nahi kiya."
            listener?.onMyraText(message, true); emitState(message); queueLocalSpeech(message)
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
        if (validatingLocalSpeech != null) return
        // A completed controlled response deliberately stays latched until genuine new
        // speech. Release it here before allocating the new identity; the previous order
        // returned early and left real VAD speech with speechTimingTurnId=0.
        if (!responseArbiter.acceptsOrdinaryModel() && responseArbiter.released()) {
            responseArbiter.releaseIfComplete()
        }
        if (!responseArbiter.acceptsOrdinaryModel()) return
        if (ordinaryModelAudioGate.isSpeechActive()) return
        speechActivityStartedAt = android.os.SystemClock.elapsedRealtime()
        speechActivityEndedAt = 0L
        if (activeTurnId == 0L) activeTurnId = ++turnSequence
        speechTimingTurnId = activeTurnId
        voiceTurnIdentities.begin(activeTurnId, speechActivityStartedAt)
        responseArbiter.begin(activeTurnId)
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
        val endingTurnId = speechTimingTurnId.takeIf { it > 0L }
            ?: voiceTurnIdentities.current()?.userTurnId
            ?: 0L
        if (endingTurnId == 0L) {
            voiceLog("VOICE_ACTION_IDENTITY_INVALID speechTurnId=0 runtimeTurnId=${GeneralAgentRuntimeStore.runtime.activeTask()?.turnId ?: 0L} reason=speech_end_without_identity")
        } else {
            speechTimingTurnId = endingTurnId
            voiceTurnIdentities.speechEnded(endingTurnId, speechActivityEndedAt)
        }
        voiceLog("speechActivityEnd turnId=$endingTurnId at=$speechActivityEndedAt")
        ordinaryModelAudioGate.onSpeechActivityEnded()
        voiceLog(
            "authoritative_user_turn_complete turnId=$activeTurnId modelGenerationId=$earlyModelAudioGenerationId " +
                "speechActivityEndedAt=$speechActivityEndedAt authoritativeUserTurnCompleteAt=$speechActivityEndedAt " +
                "speechTimingTurnId=$speechTimingTurnId speechDurationMs=${(speechActivityEndedAt - speechActivityStartedAt).coerceAtLeast(0L)} " +
                "source=local_vad userSpeechActive=false earlyModelAudioBufferedCount=${earlyModelAudio.size} " +
                "earlyModelAudioBufferedBytes=$earlyModelAudioBytes"
        )
        voiceLog("authoritativeTurnComplete turnId=$speechTimingTurnId at=$speechActivityEndedAt speechEndToAuthoritativeTurnMs=0")
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
        chunks.forEach { audio?.queueAudio(it, generationId, "MODEL") }
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
                "turnId=$ownerTurnId responseOwner=CONTROLLED_LOCAL localSpeechQueuedAt=$localSpeechQueuedAt " +
                "actionToReplyQueuedMs=${if (latestActionDispatchedAt > 0L) localSpeechQueuedAt - latestActionDispatchedAt else -1L}"
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
        localSpeechAudio.forEach { audio?.queueAudio(it, controlledGenerationId, "CONTROLLED_LOCAL") }
        localSpeechFirstPlaybackWriteAt = android.os.SystemClock.elapsedRealtime()
        voiceLog(
            "controlled_first_playback_write turnId=${responseArbiter.turnId} generationId=$controlledGenerationId " +
                "firstPlaybackWriteAt=$localSpeechFirstPlaybackWriteAt queuedToFirstPlaybackMs=${localSpeechFirstPlaybackWriteAt - localSpeechQueuedAt} " +
                "speechEndToFirstPlaybackMs=${if (speechActivityEndedAt > 0L) localSpeechFirstPlaybackWriteAt - speechActivityEndedAt else -1L} " +
                "firstAudioToPlaybackMs=${if (localSpeechFirstAudioReceivedAt > 0L) localSpeechFirstPlaybackWriteAt - localSpeechFirstAudioReceivedAt else -1L}"
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
            localSpeechAudio.forEach { audio?.queueAudio(it, controlledGenerationId, "CONTROLLED_LOCAL") }
            localSpeechFirstPlaybackWriteAt = android.os.SystemClock.elapsedRealtime()
            voiceLog(
                "controlled_first_playback_write turnId=${responseArbiter.turnId} generationId=$controlledGenerationId " +
                    "firstPlaybackWriteAt=$localSpeechFirstPlaybackWriteAt queuedToFirstPlaybackMs=${localSpeechFirstPlaybackWriteAt - localSpeechQueuedAt} " +
                    "speechEndToFirstPlaybackMs=${if (speechActivityEndedAt > 0L) localSpeechFirstPlaybackWriteAt - speechActivityEndedAt else -1L} " +
                    "firstAudioToPlaybackMs=${if (localSpeechFirstAudioReceivedAt > 0L) localSpeechFirstPlaybackWriteAt - localSpeechFirstAudioReceivedAt else -1L}"
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
        if (!screenResponseActive && !ordinaryModelAudioGate.isSpeechActive()) {
            speechTimingTurnId = 0L
            speechActivityStartedAt = 0L
            speechActivityEndedAt = 0L
        }
        if (!screenResponseActive) {
            armedScreenQuestion = ""
            armedScreenQuestionTurnId = 0L
            armedScreenQuestionDetectedAt = 0L
            armedScreenQuestionFinalCommitted = false
            earlyScreenQuestionText = ""
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
        fastVisualTurns.cancel()
        visualDeadlineExecutor.shutdownNow()
        visualFrameDeliveryExecutor.shutdownNow()
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
        fun notifyScreenProjectionPermissionResult(granted: Boolean) {
            if (!granted) instance?.mainHandler?.post {
                instance?.voiceLog("continuous_screen_permission_denied")
                instance?.queueLocalSpeech("Screen sharing permission allow nahi hui.", allowUntranscribedAudio = false)
            }
        }
        private const val CHANNEL_ID = "myra_voice"
        private const val NOTIFICATION_ID = 1001
        private const val MEMORY_COMMAND_PAUSE_MS = 450L
        private const val DELETE_CLARIFICATION_TIMEOUT_MS = 30_000L
        private const val PERSONAL_MEMORY_PAUSE_MS = 450L
        private const val PERSONAL_MEMORY_CONFIRMATION_MS = 30_000L
        private const val LOCAL_SPEECH_AUDIO_DRAIN_MS = 800L
        private const val SCREEN_QUERY_DIAGNOSTIC_TIMEOUT_MS = 8_000L
        private const val ACCESSIBILITY_VISUAL_CACHE_MAX_AGE_MS = 900L
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
