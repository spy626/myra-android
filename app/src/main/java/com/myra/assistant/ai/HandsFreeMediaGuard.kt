package com.myra.assistant.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale

/** Prevents external media speech from becoming a LYRA turn while remaining hands-free. */
class HandsFreeMediaGuard(context: Context) {
    enum class Gate { OPEN, WAKE_DETECTED, BLOCK }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val probe = StringBuilder()
    private var awakeUntil = 0L
    private var focusRequest: AudioFocusRequest? = null
    private var listeningFocusHeld = false
    private val releaseRunnable = Runnable { finishInteraction() }

    fun inspect(transcriptPart: String): Gate {
        if (!isExternalMediaPlaying()) { probe.clear(); return Gate.OPEN }
        if (isAwake()) return Gate.OPEN
        probe.append(' ').append(normalize(transcriptPart))
        if (probe.length > 120) probe.delete(0, probe.length - 120)
        return if (WAKE_PHRASE.containsMatchIn(probe)) {
            probe.clear(); beginInteraction(); Gate.WAKE_DETECTED
        } else Gate.BLOCK
    }

    fun allowModelResponse(): Boolean = !isExternalMediaPlaying() || isAwake()
    fun beginAssistantTurn() {
        // AudioEngine becomes the sole focus owner during audible playback.
        // Previously every audio chunk created another request and overwrote the
        // reference to the old one, leaking focus owners until playback requests failed.
        abandonListeningFocus("assistant_playback")
        awakeUntil = SystemClock.elapsedRealtime() + LISTEN_WINDOW_MS
    }
    fun isAwake(): Boolean = SystemClock.elapsedRealtime() < awakeUntil
    fun isExternalMediaPlaying(): Boolean = audioManager.isMusicActive

    private fun beginInteraction() {
        awakeUntil = SystemClock.elapsedRealtime() + LISTEN_WINDOW_MS
        handler.removeCallbacks(releaseRunnable)
        if (focusRequest == null) focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAcceptsDelayedFocusGain(false).setOnAudioFocusChangeListener { }.build()
        if (!listeningFocusHeld) listeningFocusHeld = audioManager.requestAudioFocus(requireNotNull(focusRequest)) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        handler.postDelayed(releaseRunnable, LISTEN_WINDOW_MS)
    }

    fun finishInteraction() {
        awakeUntil = 0L
        handler.removeCallbacks(releaseRunnable)
        abandonListeningFocus("interaction_finished")
        probe.clear()
    }

    fun release() = finishInteraction()

    private fun abandonListeningFocus(reason: String) {
        if (listeningFocusHeld) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        listeningFocusHeld = false
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    companion object {
        private const val LISTEN_WINDOW_MS = 10_000L
        private val WAKE_PHRASE = Regex("(?:hey|hi|हाय|हे)\\s+(?:lyra|lira|leera|लीरा|लायरा|myra|mayra|mira|मायरा)")
    }
}
