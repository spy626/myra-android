package com.myra.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.myra.assistant.diagnostics.VoicePipelineLogger
import java.util.concurrent.atomic.AtomicLong

class AudioFocusManager(context: Context, private val onLost: () -> Unit, private val onGained: () -> Unit) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        VoicePipelineLogger.debug("audio_focus_change focusRequestId=$activeRequestId change=$change")
        if (change == AudioManager.AUDIOFOCUS_GAIN) onGained() else if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLost()
    }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val duckRequest = requestFor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    private val transientRequest = requestFor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    @Volatile private var held = false
    @Volatile private var heldRequest: AudioFocusRequest? = null
    @Volatile private var activeRequestId = 0L
    private fun requestFor(gain: Int): AudioFocusRequest? = if (Build.VERSION.SDK_INT >= 26) {
        AudioFocusRequest.Builder(gain).setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false).setOnAudioFocusChangeListener(listener).build()
    } else null

    fun request(playbackGeneration: Long = 0L, screenQueryId: String = "", forceTransient: Boolean = false): Boolean {
        if (held) return true
        activeRequestId = requestIds.incrementAndGet()
        val initialGain = AudioFocusGainPolicy.initialGain(forceTransient)
        var selected = if (initialGain == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) transientRequest else duckRequest
        var requestedGain = if (initialGain == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) "TRANSIENT" else "TRANSIENT_MAY_DUCK"
        var raw = requestRaw(
            selected,
            initialGain
        )
        if (!forceTransient && raw != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            raw = requestRaw(transientRequest, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            selected = transientRequest
            requestedGain = "TRANSIENT"
        }
        held = raw == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        heldRequest = selected.takeIf { held }
        VoicePipelineLogger.debug(
            "audio_focus_request focusRequestId=$activeRequestId requestedGain=$requestedGain " +
                "usage=USAGE_ASSISTANT contentType=CONTENT_TYPE_SPEECH rawResult=$raw " +
                "state=${AudioFocusResultPolicy.interpret(raw)} playbackGeneration=$playbackGeneration screenQueryId=$screenQueryId"
        )
        return held
    }
    private fun requestRaw(request: AudioFocusRequest?, gain: Int): Int = if (Build.VERSION.SDK_INT >= 26) {
        manager.requestAudioFocus(requireNotNull(request))
    } else @Suppress("DEPRECATION") manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, gain)

    fun abandon(reason: String = "playback_complete") {
        if (!held) return
        if (Build.VERSION.SDK_INT >= 26) heldRequest?.let(manager::abandonAudioFocusRequest)
        else @Suppress("DEPRECATION") manager.abandonAudioFocus(listener)
        VoicePipelineLogger.debug("audio_focus_abandoned focusRequestId=$activeRequestId reason=$reason")
        held = false
        heldRequest = null
    }

    private companion object { val requestIds = AtomicLong(0) }
}

internal object AudioFocusResultPolicy {
    fun interpret(raw: Int): String = when (raw) {
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "GRANTED"
        AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "DELAYED"
        else -> "FAILED"
    }
}

internal object AudioFocusGainPolicy {
    fun initialGain(screenResponse: Boolean): Int =
        if (screenResponse) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
}
