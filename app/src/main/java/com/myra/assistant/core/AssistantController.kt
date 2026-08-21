package com.myra.assistant.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.myra.assistant.commands.Command
import com.myra.assistant.commands.CommandExecutor
import com.myra.assistant.commands.CommandParser
import com.myra.assistant.commands.CommandValidator
import com.myra.assistant.phone.AppActionExecutor
import com.myra.assistant.security.ActionAuditLogger
import com.myra.assistant.voice.TextToSpeechManager
import com.myra.assistant.voice.VoiceResponseFormatter
import java.util.concurrent.CopyOnWriteArraySet

class AssistantController(context: Context) {
    interface Listener {
        fun onStateChanged(state: AssistantState)
        fun onResult(command: Command, result: AssistantResult)
    }

    private val appContext = context.applicationContext
    private val validator = CommandValidator(appContext)
    private val executor = CommandExecutor(AppActionExecutor(appContext))
    private val tts = TextToSpeechManager(appContext)
    private val audit = ActionAuditLogger(appContext)
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var state: AssistantState = AssistantState.IDLE
        private set
    @Volatile var continuousListening = true

    fun addListener(listener: Listener) { listeners += listener; listener.onStateChanged(state) }
    fun removeListener(listener: Listener) { listeners -= listener }

    @Synchronized fun transition(next: AssistantState) {
        if (!AssistantStatePolicy.canTransition(state, next)) {
            state = AssistantState.ERROR
            main.post { listeners.forEach { it.onStateChanged(AssistantState.ERROR) } }
            return
        }
        state = next
        main.post { listeners.forEach { it.onStateChanged(next) } }
    }

    fun processText(text: String, speak: Boolean = true): AssistantResult {
        transition(AssistantState.PROCESSING)
        val command = CommandParser.parse(text)
        return processCommand(command, speak, alreadyProcessing = true)
    }

    fun processCommand(command: Command, speak: Boolean = true, alreadyProcessing: Boolean = false, notifyListeners: Boolean = true, onSpeechFinished: (() -> Unit)? = null): AssistantResult {
        if (!alreadyProcessing) transition(AssistantState.PROCESSING)
        val rawResult = validator.validate(command) ?: run {
            transition(AssistantState.EXECUTING_ACTION)
            executor.execute(command)
        }
        val preferences = appContext.getSharedPreferences("myra", Context.MODE_PRIVATE)
        val name = preferences.getString("user_name", "Zopy").orEmpty().ifBlank { "Zopy" }
        val personality = preferences.getString("personality", "GF").orEmpty().ifBlank { "GF" }
        val result = rawResult.copy(
            spokenMessage = VoiceResponseFormatter.format(command, rawResult, name, personality)
        )
        audit.record(result.actionType, result.target, result.success, result.verified)
        val silentRepeatedScroll = command.type == com.myra.assistant.commands.CommandType.YOUTUBE_SCROLL_REPEAT && result.success
        if (notifyListeners && !silentRepeatedScroll) main.post { listeners.forEach { it.onResult(command, result) } }
        if (speak && !silentRepeatedScroll) {
            transition(AssistantState.SPEAKING)
            tts.speak(result.spokenMessage) {
                resumeAfterSpeech(result.shouldResumeListening)
                onSpeechFinished?.invoke()
            }
        } else {
            resumeAfterSpeech(result.shouldResumeListening)
            onSpeechFinished?.invoke()
        }
        return result
    }

    fun speakMessage(message: String, onFinished: (() -> Unit)? = null) {
        transition(AssistantState.SPEAKING)
        tts.speak(message) { resumeAfterSpeech(true); onFinished?.invoke() }
    }

    private fun resumeAfterSpeech(shouldResume: Boolean) {
        if (!shouldResume || !continuousListening) { transition(AssistantState.IDLE); return }
        transition(AssistantState.RESUMING_WAKE_WORD)
        transition(AssistantState.WAKE_WORD_LISTENING)
    }

    fun stop() { tts.stop(); transition(AssistantState.STOPPED) }
    fun resume() { transition(AssistantState.IDLE); if (continuousListening) transition(AssistantState.WAKE_WORD_LISTENING) }
    fun release() { tts.release(); listeners.clear() }
}
