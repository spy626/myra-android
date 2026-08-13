package com.myra.assistant.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.app.ActivityManager
import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.databinding.ActivityMainBinding
import com.myra.assistant.databinding.SheetSettingsBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject
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
        b.settingsButton.setOnClickListener { showSettings() }
        updateDeviceStatus()
        b.connectButton.setOnClickListener { if (live == null) connect() else disconnect() }
        b.sendButton.setOnClickListener {
            if (live == null) { showStatus("Connect to MYRA first — tap the mic") }
            else b.textInput.text.toString().trim().takeIf { it.isNotEmpty() }?.let { live?.sendText(it); addBubble(it, true); b.textInput.text.clear() }
        }
        b.stopButton.setOnClickListener { audio?.interrupt(); live?.interrupt(); showStatus("Stopped") }
        b.muteButton.setOnClickListener { muted = !muted; audio?.setMuted(muted); b.muteButton.alpha = if (muted) 1f else .6f; showStatus(if (muted) "Microphone muted" else "Sun rahi hoon…") }
    }
    private fun updateDeviceStatus() {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0) level * 100 / scale else 0
        val memory = ActivityManager.MemoryInfo().also { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val totalGb = kotlin.math.ceil(memory.totalMem / 1073741824.0).toInt()
        b.deviceText.text = "$percent%\n${totalGb}GB"
        b.timeText.text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
    }

    private fun connect() {
        val p = getSharedPreferences("myra", MODE_PRIVATE); val key = p.getString("api_key", "") ?: ""
        val name = p.getString("user_name", "Friend") ?: "Friend"; val personality = p.getString("personality", "GF") ?: "GF"
        if (key.isBlank()) { showSettings(); showStatus("Add your API key first"); return }
        val prompt = systemPrompt(name, personality)
        audio = AudioEngine(this)
        live = GeminiLiveClient(key, p.getString("model", "gemini-3.1-flash-live-preview")!!, p.getString("voice", "Aoede")!!, prompt)
        live?.onState = { runOnUiThread { showStatus(it); b.orb.state = if (it == "Connecting…") OrbAnimationView.State.CONNECTING else OrbAnimationView.State.LISTENING } }
        live?.onReady = { runOnUiThread { b.connectButton.setColorFilter(Color.WHITE); audio?.start(); live?.sendText("Greet $name briefly and naturally.") } }
        live?.onAudio = { audio?.queueAudio(it) }
        live?.onInputTranscript = { input.append(it) }
        live?.onOutputTranscript = { output.append(it) }
        live?.onTurnComplete = { runOnUiThread { if (input.isNotBlank()) addBubble(input.toString().trim(), true); if (output.isNotBlank()) addBubble(output.toString().trim(), false); input.clear(); output.clear() } }
        live?.onError = { runOnUiThread { showStatus(it) } }
        audio?.onMicChunk = { live?.sendAudio(it) }; audio?.onAmplitude = { runOnUiThread { b.orb.amplitude = it } }
        audio?.onSpeakingChanged = { runOnUiThread { b.orb.state = if (it) OrbAnimationView.State.SPEAKING else OrbAnimationView.State.LISTENING; showStatus(if (it) "Bol rahi hoon…" else "Sun rahi hoon…") } }
        live?.connect()
    }

    private fun systemPrompt(name: String, mode: String): String {
        val style = when (mode) { "Professional" -> "Formal English, precise, no emoji, at most two sentences."; "Assistant" -> "Friendly Hinglish or English, balanced and helpful, at most three sentences."; else -> "Warm caring Hinglish girlfriend-style companion. Use natural words like haan, acha, bilkul. At most three sentences." }
        val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.getDefault()).format(Date())
        return "You are MYRA speaking ALOUD to $name. Current date/time: $now. $style Keep every response natural, conversational and safe."
    }
    private fun showSettings() {
        val dialog = BottomSheetDialog(this, com.google.android.material.R.style.Theme_Design_BottomSheetDialog)
        val s = SheetSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(s.root)
        dialog.behavior.peekHeight = (resources.displayMetrics.heightPixels * .86f).toInt()
        dialog.behavior.isFitToContents = true
        val p = getSharedPreferences("myra", MODE_PRIVATE)
        val modelLabels = arrayOf("Flash Live (Latest · Fast)", "Native Audio (Human Voice)")
        val modelIds = arrayOf("gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-preview-12-2025")
        val voiceLabels = arrayOf("Aoede (Female)", "Charon (Male)", "Kore (Female)", "Fenrir (Male)", "Puck (Male)", "Leda (Female)", "Orus (Male)", "Zephyr (Female)")
        val voiceIds = arrayOf("Aoede", "Charon", "Kore", "Fenrir", "Puck", "Leda", "Orus", "Zephyr")
        var modelIndex = modelIds.indexOf(p.getString("model", modelIds[0])).coerceAtLeast(0)
        var voiceIndex = voiceIds.indexOf(p.getString("voice", voiceIds[0])).coerceAtLeast(0)
        s.sheetApiKey.setText(p.getString("api_key", "")); s.sheetName.setText(p.getString("user_name", "Zopy"))
        s.modelChoice.text = modelLabels[modelIndex]; s.voiceChoice.text = voiceLabels[voiceIndex]
        when (p.getString("personality", "GF")) { "Professional" -> s.proMode.isChecked = true; "Assistant" -> s.assistantMode.isChecked = true; else -> s.gfMode.isChecked = true }
        s.micStatus.text = if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "Granted ✓" else "Not yet granted — tap the mic to allow"
        fun pick(title: String, labels: Array<String>, selected: Int, chosen: (Int) -> Unit) {
            AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, selected) { d, which -> chosen(which); d.dismiss() }.show()
        }
        s.modelChoice.setOnClickListener { pick("AI Model", modelLabels, modelIndex) { modelIndex = it; s.modelChoice.text = modelLabels[it] } }
        s.voiceChoice.setOnClickListener { pick("Voice", voiceLabels, voiceIndex) { voiceIndex = it; s.voiceChoice.text = voiceLabels[it] } }
        val contacts = JSONArray(p.getString("prime_contacts_json", "[]"))
        fun renderContacts() {
            s.contactList.removeAllViews()
            for (i in 0 until contacts.length()) {
                val item = contacts.getJSONObject(i)
                val row = TextView(this).apply {
                    text = "${item.optString("name")}  ·  ${item.optString("number")}    ×"
                    setTextColor(Color.LTGRAY); textSize = 13f; setPadding(16, 14, 16, 14); setBackgroundResource(com.myra.assistant.R.drawable.bg_field)
                    setOnClickListener { contacts.remove(i); renderContacts() }
                }
                s.contactList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })
            }
        }
        renderContacts()
        s.addContact.setOnClickListener {
            val n=s.contactName.text.toString().trim(); val number=s.contactNumber.text.toString().trim()
            if (n.isNotEmpty() && number.isNotEmpty()) { contacts.put(JSONObject().put("name",n).put("number",number)); s.contactName.text.clear(); s.contactNumber.text.clear(); renderContacts() }
        }
        s.closeSettings.setOnClickListener { dialog.dismiss() }
        s.sheetSave.setOnClickListener {
            val personality = when (s.personalityGroup.checkedRadioButtonId) { s.proMode.id -> "Professional"; s.assistantMode.id -> "Assistant"; else -> "GF" }
            p.edit().putString("api_key",s.sheetApiKey.text.toString().trim()).putString("user_name",s.sheetName.text.toString().trim())
                .putString("model",modelIds[modelIndex]).putString("voice",voiceIds[voiceIndex]).putString("personality",personality)
                .putString("prime_contacts_json",contacts.toString()).apply()
            Toast.makeText(this,"Settings saved",Toast.LENGTH_SHORT).show(); dialog.dismiss()
            if (live != null) { disconnect(); showStatus("Tap the mic to apply new settings") }
        }
        dialog.show()
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
