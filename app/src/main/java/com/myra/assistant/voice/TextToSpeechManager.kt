package com.myra.assistant.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class TextToSpeechManager(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pending: Pair<String, (() -> Unit)?>? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            val hindi = Locale("hi", "IN")
            tts.language = if (tts.isLanguageAvailable(hindi) >= TextToSpeech.LANG_AVAILABLE) hindi else Locale.US
            pending?.also { speak(it.first, it.second) }
            pending = null
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready) { pending = text to onDone; return }
        val id = UUID.randomUUID().toString()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) { if (utteranceId == id) onDone?.invoke() }
            override fun onDone(utteranceId: String?) { if (utteranceId == id) onDone?.invoke() }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    fun stop() = tts.stop()
    fun release() { tts.stop(); tts.shutdown() }
}
