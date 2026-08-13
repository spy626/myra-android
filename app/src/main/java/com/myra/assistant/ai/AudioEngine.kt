package com.myra.assistant.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingQueue
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
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null

    fun start() {
        if (running.getAndSet(true)) return
        startPlayback()
        startRecording()
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
        recorder?.startRecording()
        thread(name = "myra-mic") {
            val data = ByteArray(3200) // 100 ms, recommended by Live API
            while (running.get()) {
                val count = recorder?.read(data, 0, data.size) ?: 0
                if (count > 0) {
                    val chunk = data.copyOf(count)
                    onAmplitude?.invoke(rms(chunk))
                    if (!muted.get() && !speaking.get()) onMicChunk?.invoke(chunk)
                }
            }
        }
    }

    private fun startPlayback() {
        val min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, 8192)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track?.play()
        thread(name = "myra-speaker") {
            while (running.get()) {
                val bytes = queue.take()
                if (speaking.compareAndSet(false, true)) onSpeakingChanged?.invoke(true)
                track?.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING)
                if (queue.isEmpty() && speaking.compareAndSet(true, false)) onSpeakingChanged?.invoke(false)
            }
        }
    }

    fun queueAudio(bytes: ByteArray) = queue.offer(bytes)
    fun setMuted(value: Boolean) { muted.set(value) }
    fun interrupt() { queue.clear(); track?.pause(); track?.flush(); track?.play(); speaking.set(false); onSpeakingChanged?.invoke(false) }
    fun release() { running.set(false); recorder?.stop(); recorder?.release(); track?.stop(); track?.release(); queue.offer(ByteArray(0)) }

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
