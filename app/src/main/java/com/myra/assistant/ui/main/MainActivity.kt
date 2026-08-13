package com.myra.assistant.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.databinding.ActivityMainBinding
import com.myra.assistant.ui.settings.SettingsActivity
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var audio: AudioEngine? = null
    private var live: GeminiLiveClient? = null
    private val input = StringBuilder(); private val output = StringBuilder()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (!it) showStatus("Microphone permission required") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.RECORD_AUDIO)
        b.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.connectButton.setOnClickListener { if (live == null) connect() else disconnect() }
        b.sendButton.setOnClickListener { b.textInput.text.toString().trim().takeIf { it.isNotEmpty() }?.let { live?.sendText(it); append("You: $it"); b.textInput.text.clear() } }
        b.connectButton.setOnLongClickListener { audio?.interrupt(); live?.interrupt(); true }
    }

    private fun connect() {
        val p = getSharedPreferences("myra", MODE_PRIVATE); val key = p.getString("api_key", "") ?: ""
        val name = p.getString("user_name", "Friend") ?: "Friend"; val personality = p.getString("personality", "GF") ?: "GF"
        if (key.isBlank()) { startActivity(Intent(this, SettingsActivity::class.java)); showStatus("Add your API key first"); return }
        val prompt = systemPrompt(name, personality)
        audio = AudioEngine(this)
        live = GeminiLiveClient(key, p.getString("model", "gemini-3.1-flash-live-preview")!!, p.getString("voice", "Aoede")!!, prompt)
        live?.onState = { runOnUiThread { showStatus(it); b.orb.state = if (it == "Connecting…") OrbAnimationView.State.CONNECTING else OrbAnimationView.State.LISTENING } }
        live?.onReady = { runOnUiThread { b.connectButton.text = "DISCONNECT"; audio?.start(); live?.sendText("Greet $name briefly and naturally.") } }
        live?.onAudio = { audio?.queueAudio(it) }
        live?.onInputTranscript = { input.append(it) }
        live?.onOutputTranscript = { output.append(it) }
        live?.onTurnComplete = { runOnUiThread { if (input.isNotBlank()) append("You: ${input.toString().trim()}"); if (output.isNotBlank()) append("MYRA: ${output.toString().trim()}"); input.clear(); output.clear() } }
        live?.onError = { runOnUiThread { showStatus(it); append("Error: $it") } }
        audio?.onMicChunk = { live?.sendAudio(it) }; audio?.onAmplitude = { runOnUiThread { b.orb.amplitude = it } }
        audio?.onSpeakingChanged = { runOnUiThread { b.orb.state = if (it) OrbAnimationView.State.SPEAKING else OrbAnimationView.State.LISTENING; showStatus(if (it) "Bol rahi hoon…" else "Sun rahi hoon…") } }
        live?.connect()
    }

    private fun systemPrompt(name: String, mode: String): String {
        val style = when (mode) { "Professional" -> "Formal English, precise, no emoji, at most two sentences."; "Assistant" -> "Friendly Hinglish or English, balanced and helpful, at most three sentences."; else -> "Warm caring Hinglish girlfriend-style companion. Use natural words like haan, acha, bilkul. At most three sentences." }
        val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.getDefault()).format(Date())
        return "You are MYRA speaking ALOUD to $name. Current date/time: $now. $style Keep every response natural, conversational and safe."
    }
    private fun append(text: String) { b.transcriptText.append("\n$text") }
    private fun showStatus(text: String) { b.statusText.text = text }
    private fun disconnect() { live?.disconnect(); audio?.release(); live = null; audio = null; b.connectButton.text = "CONNECT"; b.orb.state = OrbAnimationView.State.IDLE; showStatus("Disconnected") }
    override fun onDestroy() { disconnect(); super.onDestroy() }
}
