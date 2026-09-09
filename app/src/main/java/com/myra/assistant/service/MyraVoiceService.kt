Warning: truncated output (original token count: 81451)
Total output lines: 5321

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
import com.myra.assistant.diagnostics.TurnLatencyTelemetry
import com.myra.assistant.diagnostics.TurnLatencyTelemetry.Field
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
import com.myra.assistant.data.memory.BestFriendNameCanonicalizer
import com.myra.assistant.data.memory.BestFriendNameCorrection
import com.myra.assistant.data.memory.ClarifiedNameResult
import com.myra.assistant.data.memory.ContextualRelationshipMemoryExtractor
import com.myra.assistant.data.memory.CorrectionSuccessPolicy
import com.myra.assistant.data.memory.LyraMemoryDatabase
import com.myra.assistant.data.memory.MemoryCommand
import com.myra.assistant.data.memory.MemoryCommandParser
import com.myra.assistant.data.memory.MemoryCommandReplyFormatter
import com.myra.assistant.data.memory.MemoryCandidate
import com.myra.assistant.data.memory.PersonalMemoryExtractor
import com.myra.assistant.data.memory.PersonLinkedMemoryExtractor
import com.myra.assistant.data.memory.PersonalMemoryRecallFormatter
import com.myra.assistant.data.memory.MemoryRepository
import com.myra.assistant.data.memory.MemoryBrainCoordinator
import com.myra.assistant.data.memory.MemoryBrainOutcome
import com.myra.assistant.data.memory.MemoryRecallType
import com.myra.assistant.data.memory.MemoryWorkingContext
import com.myra.assistant.data.memory.MemoryRelationshipPolicy
import com.myra.assistant.data.memory.SavedMemoryContextFormatter
import com.myra.assistant.data.memory.MemoryWriteResult
import com.myra.assistant.data.memory.MemorySafetyPolicy
import com.myra.assistant.data.memory.MemorySaveDecision
import com.myra.assistant.data.memory.MemoryCategory
import com.myra.assistant.data.memory.MemorySensitivity
import com.myra.assistant.data.memory.MemorySemanticFrame
import com.myra.assistant.data.memory.MemorySemanticIntent
import com.myra.assistant.data.memory.MemoryTemporalScope
import com.myra.assistant.data.memory.PersonRelationship
import com.myra.assistant.data.memory.StagedMemoryProposalPolicy
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
    private val stagedMemorySemantics = java.util.concurrent.ConcurrentHashMap<Long, List<MemorySemanticFrame>>()
    private data class PendingMemoryRecall(
        val query: String,
        val type: MemoryRecallType,
        val result: kotlinx.coroutines.CompletableDeferred<MemoryBrainOutcome.Recalled> =
            kotlinx.coroutines.CompletableDeferred()
    )
    private val stagedMemoryRecalls = java.util.concurrent.ConcurrentHashMap<Long, PendingMemoryRecall>()
    private var memoryResponsePendingTurnId = 0L
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
    private val turnLatency = TurnLatencyTelemetry(::voiceLog)
    private val scrollContinuationTelemetry = com.myra.assistant.diagnostics.ScrollContinuationTelemetry(::voiceLog)
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
    private var lastVerifiedVisualResponseAt = 0L
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
                }
            )
        ))
    }
    private val memoryRepository by lazy { MemoryRepository(LyraMemoryDatabase.get(this).memoryDao()) }
    private val memoryBrain by lazy { MemoryBrainCoordinator(memoryRepository) }
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
                turnLatency.record(activeTurnId, Field.FIRST_TOOL_PROPOSAL, android.os.SystemClock.elapsedRealtime())
                mainHandler.post { handleSemanticToolCall(id, functionName, args) }
            }
            client.onServerEvent = { _, _ ->
                turnLatency.record(activeTurnId, Field.FIRST_SERVER_EVENT, android.os.SystemClock.elapsedRealtime())
            }
            client.onAudio = { pcm, modelGenerationId ->
                val audioReceivedAt = android.os.SystemClock.elapsedRealtime()
                turnLatency.record(activeTurnId, Field.FIRST_MODEL_AUDIO_PACKET, audioReceivedAt, modelGenerationId)
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
                            turnLatency.record(activeTurnId, Field.FIRST_ACCEPTED_MODEL_AUDIO, audioReceivedAt, modelGenerationId)
                            acceptedModelGenerationForTurn = modelGenerationId
                            mediaGuard.beginAssistantTurn()
                            audio?.setPlaybackContext(modelGenerationId, responseOwner = "MODEL")
                            audio?.setBargeInEnabled(true)
                            audio?.queueAudio(pcm, modelGenerationId, "MODEL")
                            voiceLog(
                                "route_decision turnId=$activeTurnId modelGenerationId=$modelGenerationId responseOwner=MODEL " +
                                    "route=ordinary_model accepted=true firstModelAudioAcceptedAt=${turnLatency.firstAcceptedAudioAt(activeTurnId, modelGenerationId)} " +
                                    "speechEndToFirstAcceptedModelAudioMs=${turnLatency.firstAcceptedAudioAt(activeTurnId, modelGenerationId)?.let { if (speechActivityEndedAt > 0 && it >= speechActivityEndedAt) (it - speechActivityEndedAt).toString() else "NA" } ?: "NA"} bytes=${pcm.size}"
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
                val completedAt = android.os.SystemClock.elapsedRealtime()
                turnLatency.record(activeTurnId, Field.MODEL_GENERATION_COMPLETED, completedAt, modelGenerationId)
                voiceLog("model_generation_complete turnId=$activeTurnId modelGenerationId=$modelGenerationId at=$completedAt")
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
                    turnLatency.record(activeTurnId, Field.INPUT_STARTED, inputTurnStartedAt)
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
                    FastVisualRequestClassifier.classify(currentTranscript, hasRecentVerifiedVisualContext()) != null
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
                // Memory Brain V2 never mutates or interrupts from partial ASR. Natural
                // facts are evaluated silently only at the authoritative final turn.
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
                if (memoryBrain.needsCorrectionClarification(romanMemoryTranscript)) {
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
                    // Memory-looking partial speech may reserve response ownership, but it
                    // can never execute or persist. The authoritative final turn owns the
                    // actual recall/mutation decision.
                    suppressModelForTurn = true
                    output.clear()
                    audio?.interrupt()
                    voiceLog("memory_intent_held_for_final turnId=$activeTurnId decision=WAIT_FOR_FINAL executed=false")
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
                else if (memoryResponsePendingTurnId == activeTurnId && !hideNextModelTranscript) {
                    // Keep one natural response candidate, but do not display it until the
                    // coordinator has verified the final memory outcome.
                    appendTranscript(output, transcript)
                }
                else if (responseArbiter.acceptsOrdinaryModel() && !suppressModelForTurn &&
                    !hideNextModelTranscript && mediaGuard.allowModelResponse()
                ) appendTranscript(output, transcript)
                else voiceLog("duplicate_response_prevented turnId=${responseArbiter.turnId} responseOwner=${responseArbiter.owner} route=ordinary_model_text")
            }
            client.onTurnComplete = turnComplete@ {
                turnLatency.record(activeTurnId, Field.INPUT_COMPLETED, android.os.SystemClock.elapsedRealtime())
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
                turnLatency.record(activeTurnId, Field.FINAL_TRANSCRIPT_RECEIVED, finalInputTranscriptAt)
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
                turnLatency.record(activeTurnId, Field.FINAL_TRANSCRIPT_ACCEPTED, latestTurnAcceptedAt)
                latestIntentTimingTurnId = activeTurnId
                voiceLog(
                    "finalTranscriptReady turnId=$activeTurnId at=$latestTurnAcceptedAt " +
                        "authoritativeTurnToTranscriptMs=${if (speechActivityEndedAt > 0L) latestTurnAcceptedAt - speechActivityEndedAt else -1L}"
                )
                latestActionDispatchedAt = 0L
                val previousScrollContext = WorkingTaskRuntime.store.snapshot().lastCompletedTask
                val turnDecision = UnifiedLyraAgentRuntime.agent.acceptTurn(
                    normalizedFinalUserText, activityContext, visualAwarenessPreferences.enabled, activeTurnId,
                    hasRecentVerifiedVisualContext()
                )
                latestIntentDecidedAt = android.os.SystemClock.elapsedRealtime()
                scrollContinuationTelemetry.resolution(activeTurnId, normalizedFinalUserText, latestIntentDecidedAt,
                    activityContext?.packageName, activityContext?.windowId,
                    previousScrollContext?.action == ToolCapability.ACCESSIBILITY_SCROLL.name &&
                        previousScrollContext.completionState == TaskCompletionState.SUCCESS,
                    turnDecision.intent.name)
                turnLatency.record(activeTurnId, Field.INTENT_RESOLVED, latestIntentDecidedAt)
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
                    turnLatency.record(activeTurnId, Field.TASK_CREATED, android.os.SystemClock.elapsedRealtime())
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
                val verifiedVisualContext = hasRecentVerifiedVisualContext()
                val fastVisualRequest = FastVisualRequestClassifier.classify(userText, verifiedVisualContext)
                    ?: FastVisualRequestClassifier.classify(normalizedFinalUserText, verifiedVisualContext)
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
                        val resolved = memoryBrain.resolveCorrectionAnswer(displayText)
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
                    val pendingDeleteCommand = if (pendingDelete) {
                        PendingDeleteClarification.resolve(displayText)
                    } else null
                    val explicitMemoryDecision = if (pendingDeleteCommand == null) {
                        memoryBrain.explicitCommandDecision(displayText)
                    } else null
                    if (pendingDeleteCommand != null || explicitMemoryDecision != null) {
                        pendingDeleteClarificationUntil = 0L
                        if (pendingDeleteCommand != null) handleMemoryCommand(pendingDeleteCommand)
                        else handleMemoryFinalTurn(displayText, explicitMemoryDecision!!)
                        resetTurnBuffers()
                        waitingForFreshInputAfterCommand = true
                        return@turnComplete
                    }
                    if (memoryBrain.needsCorrectionClarification(finalUtterance.correctionParserInput)) {
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
                            memoryBrain.ambiguousCorrectionTarget(displayText, recentName)
      …31451 tokens truncated…bserve
            }
            voiceLog(
                "POST_ACTION_OBSERVATION_READY taskId=${task.id} turnId=${task.turnId} stepId=${step.id} " +
                    "foregroundPackage=${after.scene.externalForegroundPackage} screenGeneration=${after.scene.generation}"
            )
            turnLatency.record(task.turnId, Field.OBSERVATION_READY, android.os.SystemClock.elapsedRealtime())
            turnLatency.record(task.turnId, Field.VERIFICATION_STARTED, android.os.SystemClock.elapsedRealtime())
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
            if (step.capability == ToolCapability.ACCESSIBILITY_SCROLL) scrollContinuationTelemetry.verified(task.id, verification.status.name)
            turnLatency.record(task.turnId, Field.VERIFICATION_COMPLETED, verificationAt)
            turnLatency.logBreakdown(task.turnId, step.capability.name)
            (runtime.activeTask() ?: runtime.lastCompletedTask())?.let { WorkingTaskRuntime.store.syncRuntime(it, after.scene) }
            voiceLog(
                "VERIFICATION_RESULT taskId=${task.id} turnId=${task.turnId} stepId=${step.id} status=${verification.status} " +
                    "expected=${verification.expected} observed=${verification.observed} recoveryCount=${runtime.activeTask()?.recoveryCount ?: task.recoveryCount}"
            )
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
                result.targetId,
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
        val request = com.myra.assistant.agent.FinalSearchHandoff.parse(raw) ?: run {
            val task = UnifiedLyraAgentRuntime.agent.currentTask()
            if (task?.interpretedGoal !in setOf(com.myra.assistant.agent.AgentGoalType.BROWSER_SEARCH,
                    com.myra.assistant.agent.AgentGoalType.WEB_SEARCH)) return false
            suppressModelForTurn = true
            output.clear()
            voiceLog("SEARCH_FINAL_HANDOFF turnId=$activeTurnId decision=CLARIFY reason=missing_query noExecution=true")
            queueLocalSpeech("Kya search karna hai?", allowUntranscribedAudio = false)
            return true
        }
        val authorizedTask = GeneralAgentRuntimeStore.runtime.activeTask()
        if (authorizedTask?.turnId != activeTurnId || ToolCapability.BROWSER_SEARCH !in authorizedTask.intent.requiredCapabilities) {
            voiceLog("SEARCH_FINAL_HANDOFF turnId=$activeTurnId decision=BLOCK reason=runtime_identity_or_capability")
            suppressModelForTurn = true
            output.clear()
            return true
        }
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
                val evidence = GeneralAgentRuntimeStore.runtime.lastCompletedTask()
                if (evidence?.turnId != turnId || evidence.actionHistory.none { it.capability == ToolCapability.BROWSER_SEARCH }) {
                    voiceLog("SEARCH_FAILURE_CLAIM_BLOCKED turnId=$turnId reason=no_same_turn_executor_attempt")
                    emitState("Sun rahi hoon…")
                    return
                }
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
        AppCommand.BatteryLevel, is AppCommand.SetFlashlight,
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
        turnLatency.begin(activeTurnId, speechActivityStartedAt)
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
        turnLatency.record(endingTurnId, Field.SPEECH_END, speechActivityEndedAt)
        turnLatency.record(endingTurnId, Field.AUTHORITATIVE_COMPLETE, speechActivityEndedAt)
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
        turnLatency.record(activeTurnId, Field.FIRST_ACCEPTED_MODEL_AUDIO, acceptedAt, generationId)
        voiceLog(
            "early_model_audio_released turnId=$activeTurnId modelGenerationId=$generationId " +
                "firstModelAudioAcceptedAt=${turnLatency.firstAcceptedAudioAt(activeTurnId, generationId)} firstPlaybackAt=$acceptedAt " +
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
            val renameOutcome = memoryBrain.processStructuredCorrection(correction)
            val renamed = (renameOutcome as? MemoryBrainOutcome.Mutated)?.result is MemoryWriteResult.Saved
            val rows = memoryRepository.logPersonIdentity(
                "after_correction renamed=$renamed", correction.oldName, correction.newName
            )
            val verified = renamed && rows.any {
                it.entityName.equals(correction.newName, ignoreCase = true) ||
                    MemoryRelationshipPolicy.personName(it.fact)
                        ?.equals(correction.newName, ignoreCase = true) == true
            } && rows.none {
                it.entityName.equals(correction.oldName, ignoreCase = true) ||
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
                "Memory update verify nahi hui. Correct naam ek baar clearly batao."
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
        val genderStyle = "$baseGenderStyle ${FriendConversationPolicy.BOSS_ASSISTANT_STYLE} Use propose_user_memory for the semantic meaning of natural memory-related turns, not only command wording. Use ADD_FACT for durable non-person facts such as preferences, communication style, projects, goals, habits, workflows, app usage, and solutions. Use UPDATE_FACT or SUPERSEDE_FACT only with the same stable semantic dimension; never guess a target key. Return a bounded operations list and include independent clauses: a temporary event can be TRANSIENT_CONTEXT while a clearly stated durable relationship is ADD_RELATIONSHIP. Relationships are additive unless the user actually ends or replaces the same relationship. Distinguish relationship removal, relationship replacement, person rename, and whole-person delete. Questions are RECALL and never mutation. When the user asks what LYRA remembers about personal information, use query_user_memory with the semantic query type: GENERAL, FRIENDS, BEST_FRIEND, or LAST_TRANSACTION. FRIENDS includes the active friendship family; BEST_FRIEND never promotes an ordinary friend. Use the user's actual supporting words as evidence. Never propose guesses, secrets, or unsupported inference; never claim a write succeeded or ask routine permission because Android waits for the authoritative final transcript and owns persistence. The user may have multiple friends or best friends. Never interpret delete, remove, or hata do as uninstalling an Android app. App uninstall is unsupported. If Android does not handle an unclear delete request, ask what memory or item the user means. When current Screen Vision frames are present, answer screen questions only from visible evidence. Never claim to see the screen without a current frame. For an explicit visible-target request, call perform_screen_action so Android accessibility selects and verifies the existing UI target; never invent coordinates or claim success before verification. Call propose_screen_memory only for a durable, non-sensitive project, goal, or preference that is directly evidenced on the screen. Never propose credentials, private messages, banking or health data, or temporary UI state."
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
    private fun stopSession() { isNaturalVoiceReady = false; connectionPreparing = false; pendingActionAfterLocalSpeech = null; readingTracker.stop(); screenCommandTurnGuard.clear(); mainHandler.removeCallbacks(idleNudgeRunnable); pendingDeleteClarificationUntil = 0L; recentRelationshipTurns.clear(); serviceScope.cancel(); mediaGuard.release(); live?.disconnect(); audio?.release(); wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null; live = null; audio = null; isRunning = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
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
        private const val DELETE_CLARIFICATION_TIMEOUT_MS = 30_000L
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
