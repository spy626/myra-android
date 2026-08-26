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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

class AudioEngine(private val context: Context) {
    var onMicChunk: ((ByteArray) -> Unit)? = null
    var onAmplitude: ((Float) -> Unit)? = null
    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private val bargeInEnabled = AtomicBoolean(false)
    @Volatile private var ignoreMicUntilMs = 0L
    private val queue = LinkedBlockingQueue<ByteArray>()
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
        recorder?.startRecording()
        micThread = thread(name = "myra-mic") {
            val data = ByteArray(3200) // 100 ms, recommended by Live API
            while (running.get()) {
                val count = runCatching { recorder?.read(data, 0, data.size) ?: 0 }.getOrDefault(0)
                if (count > 0) {
                    val chunk = data.copyOf(count)
                    onAmplitude?.invoke(rms(chunk))
                    // Never feed LYRA's own speaker output back into Gemini. Platform
                    // echo cancellation is helpful but not reliable on every phone.
                    if (MicBargeInPolicy.shouldForward(muted.get(), speaking.get(), bargeInEnabled.get()) &&
                        android.os.SystemClock.elapsedRealtime() >= ignoreMicUntilMs
                    ) onMicChunk?.invoke(chunk)
                }
            }
        }
    }

    private fun startPlayback() {
        val min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val playbackAttributes = AudioAttributes.Builder()
            // Android playback capture accepts MEDIA/GAME/UNKNOWN, not ASSISTANT.
            // Keep CONTENT_TYPE_SPEECH so routing and processing still describe voice.
            .setUsage(
                if (LyraPlaybackCapturePolicy.useCapturableMediaUsage) AudioAttributes.USAGE_MEDIA
                else AudioAttributes.USAGE_ASSISTANT
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    LyraPlaybackCapturePolicy.allowExternalPlaybackCapture
                ) {
                    setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                }
            }
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            LyraPlaybackCapturePolicy.allowExternalPlaybackCapture
        ) {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
        }
        track = AudioTrack.Builder()
            .setAudioAttributes(playbackAttributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, 8192)).setTransferMode(AudioTrack.MODE_STREAM).build()
        voiceLog(
            "audio_track_created state=${track?.state} playState=${track?.playState} " +
                "minBuffer=$min session=${track?.audioSessionId}"
        )
        speakerThread = thread(name = "myra-speaker") {
            while (running.get()) {
                val bytes = queue.poll(SPEECH_END_GRACE_MS, TimeUnit.MILLISECONDS)
                if (!running.get()) break
                if (bytes == null) {
                    // Gemini audio is delivered in network chunks. A temporarily empty
                    // queue is not the end of the sentence; wait through a short gap so
                    // listening and deferred actions resume only after the full reply.
                    if (speaking.compareAndSet(true, false)) {
                        synchronized(playbackLock) { runCatching { track?.pause() } }
                        voiceLog(
                            "playback_end queued=${queue.size} totalWrittenBytes=$writtenAudioBytes " +
                                "playState=${track?.playState}"
                        )
                        ignoreMicUntilMs = android.os.SystemClock.elapsedRealtime() + MIC_ECHO_COOLDOWN_MS
                        onSpeakingChanged?.invoke(false)
                    }
                    continue
                }
                synchronized(playbackLock) {
                    if (!running.get()) return@synchronized
                    runCatching {
                        if (track?.playState != AudioTrack.PLAYSTATE_PLAYING) track?.play()
                        if (speaking.compareAndSet(false, true)) {
                            voiceLog(
                                "playback_start bytes=${bytes.size} queued=${queue.size} " +
                                    "state=${track?.state} playState=${track?.playState}"
                            )
                            onSpeakingChanged?.invoke(true)
                        }
                        val written = track?.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING) ?: 0
                        if (written > 0) writtenAudioBytes += written
                        voiceLog(
                            "audio_track_write requested=${bytes.size} written=$written " +
                                "queued=${queue.size} playState=${track?.playState}"
                        )
                    }.onFailure { error ->
                        VoicePipelineLogger.error("audio_track_failure", error)
                    }
                }
            }
        }
    }

    fun queueAudio(bytes: ByteArray) {
        val accepted = queue.offer(bytes)
        voiceLog(
            "playback_queued seq=${++queuedAudioChunk} bytes=${bytes.size} " +
                "accepted=$accepted queueSize=${queue.size} running=${running.get()}"
        )
    }
    fun setMuted(value: Boolean) { muted.set(value) }
    fun setBargeInEnabled(value: Boolean) { bargeInEnabled.set(value) }
    fun resumeListeningNow() {
        ignoreMicUntilMs = android.os.SystemClock.elapsedRealtime()
        muted.set(false)
    }
    fun interrupt() {
        voiceLog("playback_interrupt queueSize=${queue.size} speaking=${speaking.get()}")
        queue.clear()
        bargeInEnabled.set(false)
        synchronized(playbackLock) { runCatching { track?.pause(); track?.flush() } }
        speaking.set(false)
        onSpeakingChanged?.invoke(false)
    }
    fun release() {
        if (!running.getAndSet(false)) return
        queue.offer(ByteArray(0))
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

    private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

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
