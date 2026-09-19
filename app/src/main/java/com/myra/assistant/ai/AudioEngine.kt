package com.myra.assistant.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.core.content.ContextCompat
import com.myra.assistant.diagnostics.VoicePipelineLogger
import com.myra.assistant.voice.AudioFocusManager
import com.myra.assistant.voice.AssistantPlaybackFocusPolicy
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt
import kotlin.math.max

class AudioEngine(private val context: Context) {
    var onMicChunk: ((ByteArray) -> Unit)? = null
    var onAmplitude: ((Float) -> Unit)? = null
    var onSpeakingChanged: ((Boolean) -> Unit)? = null
    var onSpeechActivityChanged: ((Boolean) -> Unit)? = null

    fun currentMediaCandidateId(): Long = mediaAwareVadGate.candidateId

    private val running = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private val bargeInEnabled = AtomicBoolean(false)
    private val playbackBargeInAccepted = AtomicBoolean(false)
    @Volatile private var ignoreMicUntilMs = 0L
    private data class AudioChunk(
        val generationId: Long,
        val responseOwner: String,
        val playbackId: Long,
        val bytes: ByteArray
    )
    private val queue = LinkedBlockingQueue<AudioChunk>()
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var micThread: Thread? = null
    private var speakerThread: Thread? = null
    private val playbackLock = Any()
    private var queuedAudioChunk = 0L
    private var writtenAudioBytes = 0L
    private val speechActivityDetector = SpeechActivityDetector()
    private val playbackSpeechActivityDetector = SpeechActivityDetector(
        startThreshold = 0.060f, continueThreshold = 0.018f, startFrames = 4, endFrames = 10
    )
    private val mediaSpeechActivityDetector = SpeechActivityDetector(
        startThreshold = 0.055f, continueThreshold = 0.020f, startFrames = 5, endFrames = 12
    )
    private val mediaAwareVadGate = MediaAwareVadGate()
    private val audioFocus by lazy { AudioFocusManager(context, {}, {}) }
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    @Volatile private var playbackGeneration = 0L
    @Volatile private var playbackScreenQueryId = ""
    @Volatile private var playbackResponseOwner = "MODEL"
    @Volatile private var playbackReferenceRms = 0f
    @Volatile private var activePlaybackId = 0L
    private var playbackSequence = 0L
    private var lastVadRejectLogAt = 0L
    private val generationOwner = AudioGenerationOwner()

    fun start() {
        if (running.getAndSet(true)) return
        startPlayback()
        startRecording()
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
        recorder?.audioSessionId?.let { session ->
            if (AcousticEchoCanceler.isAvailable()) echoCanceler = AcousticEchoCanceler.create(session)?.apply { enabled = true }
            if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(session)?.apply { enabled = true }
            if (AutomaticGainControl.isAvailable()) gainControl = AutomaticGainControl.create(session)?.apply { enabled = true }
        }
        voiceLog(
            "audio_record_config audioRecordSource=VOICE_COMMUNICATION audioSessionId=${recorder?.audioSessionId} " +
                "acousticEchoCancelerAvailable=${AcousticEchoCanceler.isAvailable()} acousticEchoCancelerEnabled=${echoCanceler?.enabled == true} " +
                "noiseSuppressorAvailable=${NoiseSuppressor.isAvailable()} noiseSuppressorEnabled=${noiseSuppressor?.enabled == true}"
        )
        recorder?.startRecording()
        micThread = thread(name = "myra-mic") {
            val data = ByteArray(1600)
            while (running.get()) {
                val count = runCatching { recorder?.read(data, 0, data.size) ?: 0 }.getOrDefault(0)
                if (count > 0) {
                    val chunk = data.copyOf(count)
                    val level = rms(chunk)
                    onAmplitude?.invoke(level)
                    if (!muted.get()) {
                        val mediaActive = audioManager.isMusicActive
                        if (!mediaActive && !speaking.get()) mediaAwareVadGate.reset()
                        val detector = when {
                            speaking.get() -> playbackSpeechActivityDetector
                            mediaActive -> mediaSpeechActivityDetector
                            else -> speechActivityDetector
                        }
                        val dynamicStart = if (speaking.get()) max(0.085f, playbackReferenceRms * 1.65f) else null
                        when (detector.update(level, dynamicStart)) {
                            SpeechActivityEvent.STARTED -> {
                                if (mediaActive && !speaking.get()) {
                                    mediaAwareVadGate.onEnergyStarted(energy = level)
                                    voiceLog("vad_decision vadEnergy=$level playbackActive=false mediaActive=true probableMediaLeak=true vadState=POSSIBLE_MEDIA vadDecision=WAIT_FOR_ASR vadConfirmationReason=media_requires_transcript")
                                    continue
                                }
                                if (speaking.get()) {
                                    val assessment = SelfPlaybackBargeInGate.assess(
                                        level, playbackReferenceRms, sustained = true, enabled = bargeInEnabled.get()
                                    )
                                    voiceLog(
                                        "vad_decision vadEnergy=$level playbackActive=true mediaActive=$mediaActive " +
                                            "probableSelfPlayback=${!assessment.accepted} selfPlaybackProbability=${assessment.selfPlaybackProbability} " +
                                            "playbackReferenceCorrelation=${assessment.playbackReferenceCorrelation} " +
                                            "bargeInDecision=${if (assessment.accepted) "ACCEPT" else "REJECT"} " +
                                            "bargeInAcceptedReason=${if (assessment.accepted) assessment.reason else "none"} " +
                                            "bargeInRejectedReason=${if (assessment.accepted) "none" else assessment.reason}"
                                    )
                                    if (!assessment.accepted) {
                                        playbackSpeechActivityDetector.reset()
                                        continue
                                    }
                                    playbackBargeInAccepted.set(true)
                                } else voiceLog("vad_decision vadEnergy=$level vadTriggerDurationMs=${if (mediaActive) 250 else 50} playbackActive=false mediaActive=$mediaActive probableSelfPlayback=false probableMediaLeak=${mediaActive} vadDecision=ACCEPT_SUSTAINED bargeInDecision=NOT_APPLICABLE")
                                onSpeechActivityChanged?.invoke(true)
                            }
                            SpeechActivityEvent.ENDED -> {
                                if (mediaActive && !speaking.get()) {
                                    when (mediaAwareVadGate.onEnergyEnded()) {
                                        MediaAwareVadGate.Result.CONFIRMED_USER -> onSpeechActivityChanged?.invoke(false)
                                        MediaAwareVadGate.Result.REJECTED_MEDIA -> voiceLog("vad_decision vadEnergy=$level mediaActive=true probableMediaLeak=true vadState=NO_SPEECH mediaLeakRejected=true vadRejectedReason=no_asr_confirmation")
                                        else -> Unit
                                    }
                                    continue
                                }
                                voiceLog("vad_decision vadEnergy=$level playbackActive=${speaking.get()} mediaActive=$mediaActive vadDecision=END")
                                onSpeechActivityChanged?.invoke(false)
                            }
                            SpeechActivityEvent.NONE -> {
                                val now = android.os.SystemClock.elapsedRealtime()
                                if ((speaking.get() || mediaActive) && level >= 0.018f && now - lastVadRejectLogAt >= 1_000L) {
                                    lastVadRejectLogAt = now
                                    voiceLog("vad_decision vadEnergy=$level playbackActive=${speaking.get()} mediaActive=$mediaActive playbackReferenceRms=$playbackReferenceRms dynamicThreshold=${dynamicStart ?: 0.055f} vadDecision=REJECT_TRANSIENT vadRejectedReason=${if (speaking.get()) "probable_self_playback" else "probable_media_leak"}")
                                }
                            }
                        }
                    }
                    if (MicBargeInPolicy.shouldForward(muted.get(), speaking.get(), bargeInEnabled.get(), playbackBargeInAccepted.get()) &&
                        android.os.SystemClock.elapsedRealtime() >= ignoreMicUntilMs
                    ) onMicChunk?.invoke(chunk)
                }
            }
        }
    }

    private fun startPlayback() {
        val min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(if (LyraPlaybackCapturePolicy.useCapturableMediaUsage) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && LyraPlaybackCapturePolicy.allowExternalPlaybackCapture) {
                    setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                }
            }.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && LyraPlaybackCapturePolicy.allowExternalPlaybackCapture) {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
        }
        track = AudioTrack.Builder()
            .setAudioAttributes(playbackAttributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, 8192)).setTransferMode(AudioTrack.MODE_STREAM).build()
        voiceLog("audio_track_created state=${track?.state} playState=${track?.playState} minBuffer=$min session=${track?.audioSessionId}")
        speakerThread = thread(name = "myra-speaker") {
            while (running.get()) {
                val chunk = queue.poll(SPEECH_END_GRACE_MS, TimeUnit.MILLISECONDS)
                if (!running.get()) break
                if (chunk == null) {
                    if (speaking.compareAndSet(true, false)) {
                        synchronized(playbackLock) { runCatching { track?.pause() } }
                        voiceLog("playback_end queued=${queue.size} totalWrittenBytes=$writtenAudioBytes playState=${track?.playState}")
                        ignoreMicUntilMs = android.os.SystemClock.elapsedRealtime() + MIC_ECHO_COOLDOWN_MS
                        audioFocus.abandon("playback_end")
                        voiceLog("audio_focus_release reason=playback_end")
                        playbackGeneration = 0L
                        generationOwner.clear()
                        activePlaybackId = 0L
                        playbackScreenQueryId = ""
                        playbackResponseOwner = "MODEL"
                        playbackSpeechActivityDetector.reset()
                        playbackBargeInAccepted.set(false)
                        mediaSpeechActivityDetector.reset()
                        mediaAwareVadGate.reset()
                        onSpeakingChanged?.invoke(false)
                    }
                    continue
                }
                if (!generationOwner.accepts(chunk.generationId, chunk.responseOwner) || chunk.generationId != playbackGeneration) {
                    voiceLog("audio_chunk_dropped_stale_generation playbackId=${chunk.playbackId} chunkGeneration=${chunk.generationId} chunkOwner=${chunk.responseOwner} activeAuthorizedGeneration=${generationOwner.generationId()} activeAudioOwner=${generationOwner.owner()} reason=stale_generation")
                    continue
                }
                synchronized(playbackLock) {
                    if (!running.get()) return@synchronized
                    if (!generationOwner.accepts(chunk.generationId, chunk.responseOwner) || chunk.generationId != playbackGeneration) {
                        voiceLog("audio_chunk_dropped_stale_generation playbackId=${chunk.playbackId} chunkGeneration=${chunk.generationId} chunkOwner=${chunk.responseOwner} activeAuthorizedGeneration=${generationOwner.generationId()} activeAudioOwner=${generationOwner.owner()} reason=generation_changed_before_write")
                        return@synchronized
                    }
                    runCatching {
                        if (track?.playState != AudioTrack.PLAYSTATE_PLAYING) track?.play()
                        if (speaking.compareAndSet(false, true)) {
                            playbackBargeInAccepted.set(false)
                            val focusGranted = audioFocus.request(
                                playbackGeneration, playbackScreenQueryId,
                                responseOwner = playbackResponseOwner,
                                forceTransient = AssistantPlaybackFocusPolicy.requiresTransient(playbackResponseOwner)
                            )
                            voiceLog("audio_focus_result granted=$focusGranted playbackGeneration=$playbackGeneration responseOwner=$playbackResponseOwner screenQueryId=$playbackScreenQueryId")
                            speechActivityDetector.reset(); playbackSpeechActivityDetector.reset(); mediaSpeechActivityDetector.reset()
                            voiceLog("playback_start playbackId=${chunk.playbackId} generationId=${chunk.generationId} bytes=${chunk.bytes.size} queued=${queue.size} state=${track?.state} playState=${track?.playState}")
                            onSpeakingChanged?.invoke(true)
                        }
                        activePlaybackId = chunk.playbackId
                        val written = track?.write(chunk.bytes, 0, chunk.bytes.size, AudioTrack.WRITE_BLOCKING) ?: 0
                        playbackReferenceRms = rms(chunk.bytes)
                        if (written > 0) writtenAudioBytes += written
                        voiceLog("audio_track_write playbackId=${chunk.playbackId} generationId=${chunk.generationId} requested=${chunk.bytes.size} written=$written queued=${queue.size} playState=${track?.playState}")
                    }.onFailure { error -> VoicePipelineLogger.error("audio_track_failure", error) }
                }
            }
        }
    }

    fun queueAudio(
        bytes: ByteArray,
        generationId: Long = playbackGeneration,
        responseOwner: String = playbackResponseOwner
    ) {
        if (!generationOwner.accepts(generationId, responseOwner) || generationId != playbackGeneration) {
            voiceLog(
                "audio_chunk_dropped_stale_generation chunkGeneration=$generationId " +
                    "activeAuthorizedGeneration=${generationOwner.generationId()} activeAudioOwner=${generationOwner.owner()} bytes=${bytes.size}"
            )
            return
        }
        val generation = generationId
        val playbackId = ++playbackSequence
        val accepted = queue.offer(AudioChunk(generation, responseOwner, playbackId, bytes.copyOf()))
        voiceLog("playback_queued playbackId=$playbackId generationId=$generation seq=${++queuedAudioChunk} bytes=${bytes.size} accepted=$accepted queueSize=${queue.size} activePlaybackId=$activePlaybackId running=${running.get()}")
    }

    fun setPlaybackContext(generationId: Long, screenQueryId: String = "", responseOwner: String = "MODEL") {
        val previous = playbackGeneration
        if (previous != 0L && generationId != 0L && previous != generationId) cancelPlayback("new_generation", generationId)
        val authorization = generationOwner.authorize(generationId, responseOwner)
        playbackGeneration = generationId
        playbackScreenQueryId = screenQueryId
        playbackResponseOwner = responseOwner
        if (authorization.concurrent) voiceLog("concurrent_generation_detected previousGeneration=${authorization.previousGeneration} replacementGeneration=$generationId owner=$responseOwner")
        voiceLog("audio_generation_owner generationId=$generationId previousGeneration=$previous owner=$responseOwner screenQueryId=$screenQueryId")
    }

    private fun cancelPlayback(reason: String, replacementGenerationId: Long = 0L) {
        val cancelledId = activePlaybackId
        queue.clear()
        playbackGeneration = replacementGenerationId
        if (replacementGenerationId == 0L) generationOwner.clear()
        synchronized(playbackLock) {
            runCatching { track?.pause() }
            runCatching { track?.flush() }
        }
        speaking.set(false)
        bargeInEnabled.set(false)
        audioFocus.abandon(reason)
        voiceLog("AUDIO_PLAYBACK_CANCELLED playbackId=$cancelledId replacementGenerationId=$replacementGenerationId reason=$reason activeAudioOwner=$playbackResponseOwner")
        onSpeakingChanged?.invoke(false)
    }

    fun setMuted(value: Boolean) {
        muted.set(value)
        if (value) speechActivityDetector.reset()
    }
    fun setBargeInEnabled(value: Boolean) { bargeInEnabled.set(value) }
    fun confirmMediaSpeechFromTranscript(text: String = ""): Boolean {
        val ended = mediaAwareVadGate.endedBeforeConfirmation()
        if (mediaAwareVadGate.confirmFromTranscript(text) == MediaAwareVadGate.Result.CONFIRMED_USER) {
            voiceLog("vad_decision mediaActive=true vadState=CONFIRMED_USER realUserConfirmed=true vadConfirmationReason=validated_asr_intent candidateId=${mediaAwareVadGate.candidateId} transcriptChars=${text.length}")
            onSpeechActivityChanged?.invoke(true)
            if (ended) onSpeechActivityChanged?.invoke(false)
            return true
        }
        return false
    }
    fun resumeListeningNow() {
        ignoreMicUntilMs = android.os.SystemClock.elapsedRealtime()
        muted.set(false)
    }
    fun interrupt() {
        voiceLog("playback_interrupt queueSize=${queue.size} speaking=${speaking.get()} activePlaybackId=$activePlaybackId generationId=$playbackGeneration")
        queue.clear()
        bargeInEnabled.set(false)
        synchronized(playbackLock) { runCatching { track?.pause(); track?.flush() } }
        audioFocus.abandon("playback_interrupt")
        voiceLog("audio_focus_release reason=playback_interrupt")
        playbackGeneration = 0L
        generationOwner.clear()
        activePlaybackId = 0L
        playbackScreenQueryId = ""
        playbackResponseOwner = "MODEL"
        playbackSpeechActivityDetector.reset()
        playbackBargeInAccepted.set(false)
        mediaSpeechActivityDetector.reset()
        mediaAwareVadGate.reset()
        speaking.set(false)
        onSpeakingChanged?.invoke(false)
    }
    fun release() {
        if (!running.getAndSet(false)) return
        queue.offer(AudioChunk(playbackGeneration, playbackResponseOwner, ++playbackSequence, ByteArray(0)))
        runCatching { recorder?.stop() }
        runCatching { micThread?.join(750) }
        runCatching { speakerThread?.join(750) }
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { gainControl?.release() }
        runCatching { recorder?.release() }
        synchronized(playbackLock) {
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
        audioFocus.abandon("engine_release")
        recorder = null
        track = null
        micThread = null
        speakerThread = null
    }

    private companion object {
        const val SPEECH_END_GRACE_MS = 350L
        const val MIC_ECHO_COOLDOWN_MS = 600L
        const val VOICE_AUDIO_DEBUG_LOGGING = true
        const val VOICE_AUDIO_LOG_TAG = "LyraVoicePipeline"
    }

    private fun voiceLog(message: String) {
        if (VOICE_AUDIO_DEBUG_LOGGING) VoicePipelineLogger.debug(message)
    }

    private fun rms(bytes: ByteArray): Float {
        if (bytes.size < 2) return 0f
        var sum = 0.0
        var i = 0
        while (i + 1 < bytes.size) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toDouble()
            sum += sample * sample; i += 2
        }
        return (sqrt(sum / (bytes.size / 2)) / 32768.0).toFloat().coerceIn(0f, 1f)
    }
}
