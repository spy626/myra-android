package com.myra.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

class AudioFocusManager(context: Context, private val onLost: () -> Unit, private val onGained: () -> Unit) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_GAIN) onGained() else if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLost()
    }
    private val request = if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener(listener).build() else null
    fun request(): Boolean = if (Build.VERSION.SDK_INT >= 26) manager.requestAudioFocus(request!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        else @Suppress("DEPRECATION") (manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    fun abandon() { if (Build.VERSION.SDK_INT >= 26) manager.abandonAudioFocusRequest(request!!) else @Suppress("DEPRECATION") manager.abandonAudioFocus(listener) }
}
