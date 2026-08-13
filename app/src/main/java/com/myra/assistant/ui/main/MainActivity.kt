package com.myra.assistant.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
    private var muted = false
    private val input = StringBuilder(); private val output = StringBuilder()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (!it) showStatus("Microphone permission required") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.RECORD_AUDIO)
        b.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.connectButton.setOnClickListener { if (live == null) connect() else disconnect() }
        b.sendButton.setOnClickListener { b.textInput.text.toString().trim().takeIf { it.isNotEmpty() }?.let { live?.sendText(it); addBubble(it, true); b.textInput.text.clear() } }
        b.stopButton.setOnClickListener { audio?.interrupt(); live?.interrupt(); showStatus("Stopped") }
        b.muteButton.setOnClickListener { muted = !muted; audio?.setMuted(muted); b.muteButton.alpha = if (muted) 1f else .6f; showStatus(if (muted) "Microphone muted" else "Sun rahi hoon…") }
    }

    private fun connect() {
        val p = getSharedPreferences("myra", MODE_PRIVATE); val key = p.getString("api_key", "") ?: ""
        val name = p.getString("user_name", "Friend") ?: "Friend"; val personality = p.getString("personality", "GF") ?: "GF"
        if (key.isBlank()) { startActivity(Intent(this, SettingsActivity::class.java)); showStatus("Add your API key first"); return }
        val prompt = systemPrompt(name, personality)
        audio = AudioEngine(this)
        live = GeminiLiveClient(key, p.getString("model", "gemini-3.1-flash-live-preview")!!, p.getString("voice", "Aoede")!!, prompt)
        live?.onState = { runOnUiThread { showStatus(it); b.orb.state = if (it == "Connecting…") OrbAnimationView.State.CONNECTING else OrbAnimationView.State.LISTENING } }
        live?.onReady = { runOnUiThread { b.connectButton.setColorFilter(Color.WHITE); audio?.start(); live?.sendText("Greet $name briefly and naturally.") } }
        live?.onAudio = { audio?.queueAudio(it) }
        live?.onInputTranscript = { input.append(it) }
        live?.onOutputTranscript = { output.append(it) }
        live?.onTurnComplete = { runOnUiThread { if (input.isNotBlank()) addBubble(input.toString().trim(), true); if (output.isNotBlank()) addBubble(output.toString().trim(), false); input.clear(); output.clear() } }
        live?.onError = { runOnUiThread { showStatus(it); addBubble(it, false, true) } }
        audio?.onMicChunk = { live?.sendAudio(it) }; audio?.onAmplitude = { runOnUiThread { b.orb.amplitude = it } }
        audio?.onSpeakingChanged = { runOnUiThread { b.orb.state = if (it) OrbAnimationView.State.SPEAKING else OrbAnimationView.State.LISTENING; showStatus(if (it) "Bol rahi hoon…" else "Sun rahi hoon…") } }
        live?.connect()
    }

    private fun systemPrompt(name: String, mode: String): String {
        val style = when (mode) { "Professional" -> "Formal English, precise, no emoji, at most two sentences."; "Assistant" -> "Friendly Hinglish or English, balanced and helpful, at most three sentences."; else -> "Warm caring Hinglish girlfriend-style companion. Use natural words like haan, acha, bilkul. At most three sentences." }
        val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.getDefault()).format(Date())
        return "You are MYRA speaking ALOUD to $name. Current date/time: $now. $style Keep every response natural, conversational and safe."
    }
    private fun addBubble(text: String, isUser: Boolean, isError: Boolean = false) {
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(if (isError) Color.rgb(255, 110, 130) else Color.rgb(238, 238, 238))
            textSize = 13f
            maxWidth = (resources.displayMetrics.widthPixels * .82f).toInt()
            setBackgroundResource(if (isUser) com.myra.assistant.R.drawable.bg_chat_user else com.myra.assistant.R.drawable.bg_chat_myra)
        }
        val row = LinearLayout(this).apply {
            gravity = if (isUser) Gravity.END else Gravity.START
            val gap = (6 * resources.displayMetrics.density).toInt()
            setPadding(0, gap / 2, 0, gap / 2)
            addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        b.chatContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        b.chatScroll.post { b.chatScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
    private fun showStatus(text: String) { b.statusText.text = text }
    private fun disconnect() { live?.disconnect(); audio?.release(); live = null; audio = null; b.connectButton.clearColorFilter(); b.orb.state = OrbAnimationView.State.IDLE; showStatus("Tap the mic to wake MYRA") }
    override fun onDestroy() { disconnect(); super.onDestroy() }
}
